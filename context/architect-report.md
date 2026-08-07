---
created: 2026-06-16
module: 4 (10xArchitect)
sources:
  L2: context/map/repo-map.md
  L3: context/changes/firestore-restore-flow/research.md
  L4: context/changes/refactor-opportunities/plan.md
  L5: context/domain/01-domain-distillation.md · 02-invariant-aggregate-refactor.md · 03-anti-corruption-layer.md
---

# Raport architektoniczny — moduł 4

## 1. Opisane projekty

Wszystkie artefakty modułu 4 dotyczą jednego repozytorium: **CCI Android**.

| Atrybut | Wartość |
|---------|---------|
| Repozytorium | `cci-android` |
| Stack | Kotlin 2.1.20, Jetpack Compose, Hilt DI, Retrofit, Room v7, Firebase Firestore/Auth, Paging3 |
| Skala | 5 dni, 139 commitów, 14 ekranów, 12 repozytoriów, 6 PagingSource, schemat Room v7 (6 DAO) |
| Przy artefaktach | L2 (mapa), L3 (research FirestoreRestoreUseCase), L4 (plan refaktoru K3+K2+K5), L5 (DDD) |

---

## 2. Mapa projektu (L2)

Źródło: `context/map/repo-map.md`, 139 commitów, okno 2026-06-11–2026-06-15.

**Centrum aktywności:** `data/` (106 dotknięć) i `ui/catalog/caps/detail/` (44 dotknięcia). Stabilne peryferia: `ui/theme/`, `navigation/Screen.kt`, 5 API serwisów Retrofit.

**Pięć kluczowych wniosków:**

1. **Najsilniejsze sprzężenie** — `data/datasource` + `ui/catalog` (15 wspólnych commitów). Zmiana kontraktu API propaguje bez buforu przez obie warstwy jednocześnie.

2. **Niewidoczny fan-out** — `model/CapExtended.kt` edytowany tylko 5 razy, ale każda edycja kaskaduje przez 11+ obszarów (PagingSource, UI, Room cache, statistics) — najwyższy fan-out w projekcie, bez testu przechwytującego kaskadę.

3. **Destruktywna operacja z dwóch miejsc** — `FirestoreRestoreUseCase.restoreIfEmpty()` wywoływana z `CCIApplication.onCreate()` AND `AuthRepository.login()`. Mutex chroni przed TOCTOU w obrębie jednej metody, ale `restoreFromFirestore()` (destruktywna: `deleteAll()` + reinserт) bez Mutexa.

4. **Cicha zależność runtime** — `fallbackToDestructiveMigration` w `di/DatabaseModule.kt` jest bezpieczna wyłącznie wtedy, gdy Firestore restore działa poprawnie. Sprzężenie między dwoma odległymi plikami niewidoczne w kodzie.

5. **Firestore — `unknown` dla statycznej analizy** — `dependency-analysis-gradle-plugin` analizuje zależności Maven, nie importy Kotlin. Cała warstwa Firestore zbadana wyłącznie przez git co-change.

---

## 3. Analiza ficzera (L3)

Źródło: `context/changes/firestore-restore-flow/research.md`.

**Dlaczego ten przepływ:** mapa wskazała `FirestoreRestoreUseCase` jako jedyną destruktywną operację w systemie, wywoływaną z dwóch miejsc, chronioną Mutexem niesymetrycznie, z warstwą Firestore całkowicie poza statyczną analizą.

**Feature overview:** UseCase ma dwie metody o różnej semantyce — `restoreIfEmpty()` (lazy guard: pobierz z Firestore tylko jeśli Room pusty; chronione Mutexem; wywoływane z `CCIApplication:34` i `AuthRepository:59`) oraz `restoreFromFirestore()` (destruktywna: `deleteAll()` kaskadowo + reinserт z Firestore; bez Mutexa; wywoływana przez `HomeViewModel:87` przy ręcznej synchronizacji). Dane wejściowe to trzy kolekcje Firestore (`/users/{uid}/binders`, `binder_pages`, `cap_positions`); wynikiem jest odbudowana hierarchia Room lub `RestoreResult.NotLoggedIn / Empty / Success`. Całość poza transakcją dla path `restoreIfEmpty()` — wstawianie orphaned items jest cicho pomijane.

**Trzy najważniejsze ryzyka:**

- **Asymetryczny Mutex (potwierdzone ast-grepem)** — `restoreIfEmptyMutex.withLock` widnieje wyłącznie w `restoreIfEmpty():49`. `restoreFromFirestore():64-78` nie ma analogicznej blokady. Scenariusz startup + user sync button równolegle może prowadzić do wyścigu na DAO Room.

- **Gałęzie `uid == null` bez testów (WYSOKIE ryzyko)** — obie metody mają early return przy `uid == null` (linie :48 i :65). Żaden test nie weryfikuje tego zachowania; `authManager.uid` to `StateFlow<String?>` zmieniany asynchronicznie — race condition między `signInWithEmail()` a `restoreIfEmpty()` jest teoretycznie możliwy.

- **`restoreFromFirestore()` success path nieprzetestowany** — `FirestoreRestoreUseCaseTest.kt` pokrywa wyłącznie rollback przy błędzie; liczniki zwracane przez `RestoreResult.Success` nigdy nie są asertowane.

---

## 4. Plan refaktoryzacji (L4)

Źródło: `context/changes/refactor-opportunities/plan.md`. Status: **wszystkie fazy zakończone** (Progress: wszystkie kroki `[x]`).

**Trzy wybrane refaktory (K3, K2, K5) — wszystkie niezależne, każdy jako osobny commit:**

| Faza | Cel | Weryfikacja |
|------|-----|-------------|
| K3 | `restoreFromFirestore()` owinięte tym samym Mutexem co `restoreIfEmpty()` — zamknięcie race condition cross-method | auto: `ktlintCheck` + `testDebugUnitTest`; ręcznie: androidTest `FirestoreRestoreUseCaseTest` + grep `withLock` → 2 wyniki |
| K2 | Ekstrakcja 20-liniowego inline mappingu z `CapPositionFirestoreService.fetchAll()` do `QueryDocumentSnapshot.toCapPositionDocument()` w `CapPositionMapper.kt` | auto: j.w.; ręcznie: `wc -l CapPositionFirestoreService.kt` skrócone o ~15 |
| K5 | Przeniesienie `fetchApiToken()` z `AuthRepository` do `SessionRepository.fetchAndStoreApiToken()` | auto: 6 testów `AuthRepositoryTest`; ręcznie: flow logowania na emulatorze + Logcat `CCI_AUTH` |

**Świadomie nie robimy:** pełnej dekompozycji `AuthRepository` (cookie/CSRF → osobna klasa), K4 (koordynator dual-callsite — Mutex obsługuje wystarczająco), K6 (CapExtended fan-out — pytanie domenowe, nie strukturalne), K1 (interfejs dla UseCase — mockk działa na klasach).

**Addendum z impl-review:** Hilt circular dependency wymusił `dagger.Lazy<AuthApiService>` zamiast bezpośredniego wstrzykiwania; aktualizacja konstruktora `SessionRepository` wymagała zmiany w `SessionAuthenticatorTest.kt:27` i `HomeViewModelTest.kt:41`.

---

## 5. Domena wg DDD (L5)

Źródła: `context/domain/01-domain-distillation.md`, `02-invariant-aggregate-refactor.md`, `03-anti-corruption-layer.md`.

**Ubiquitous language — 5 kluczowych pojęć:**

| Pojęcie | Definicja | Rozjazd model↔kod |
|---------|-----------|-------------------|
| **Cap (Kapsel)** | Podstawowy obiekt kolekcjonerski z bazy crowncaps.info | — |
| **isInCollection** | Pole binarne z API — autorytatywne źródło posiadania | Traktowane jako 1 z 4 rozproszonych źródeł stanu (nie jako jedyne) |
| **Stan posiadania** | Trójwartościowy: MISSING / PURCHASED / IN_COLLECTION | `CapStatus` enum wyłącznie w `CapDetailViewModel.kt:32`; 3 inne ViewModele re-implementują własne heurystyki |
| **Klaser (Binder)** | Album kolekcjonera z hierarchią Binder → BinderPage (1–15) → CapPosition (1–35) | `Binder` to `@Entity(tableName="binder")` eksponowany jako `List<Binder>` w publicznym `BindersUiState` |
| **PendingCap** | Kapsel kupiony, czekający na przypisanie do klasera | `PendingCapRepository` istnieje poprawnie (Room, Flow), ale żaden ViewModel go nie wstrzykuje — faktyczny mechanizm to `PurchasedCapsLocalStore` (SharedPreferences) |

**Niezmiennik #1 i agregat:**

> „Stan posiadania kapsla jest zawsze dokładnie jednym z trzech: MISSING, PURCHASED, IN_COLLECTION. Przejście PURCHASED → IN_COLLECTION jest atomowe."

Należy do agregatu **CollectionEntry** (zaprojektowanego w `02-invariant-aggregate-refactor.md`). Dziś egzekwowany słabo: przejście `PURCHASED → IN_COLLECTION` w `CapDetailViewModel.saveAssignment():162-168` nie usuwa kapsla z `PurchasedCapsLocalStore` — kapsel jest jednocześnie w obu lokalnych stanach. Jedyną ochroną przed błędnym wyświetleniem jest kompensacja obliczeniowa w `PurchasedViewModel.kt:48-49`.

**Anti-Corruption Layer:** Room `@Entity` (Binder, BinderPage, CapPosition, CapCache) przecieka przez 4 warstwy do 6 plików UI — w tym do sygnatury Composable (`CapDetailView.kt:47-48` przyjmuje `List<BinderPage>`). Pole `firestoreId` z `BinderPage` (klucz dokumentu Firestore) jest bezpośrednio widoczne w stanie ViewModel. Plan ACL w `03-anti-corruption-layer.md` wprowadza 5 nowych domain models (`BinderView`, `BinderPageView`, `CapSlot`, `CatalogStatus`, `FlaggedCapView`) i lokalizuje mapowanie Room→domain wyłącznie w repozytoriach. Kryterium: `grep -r "datasource.local.entity" ui/` → 0 wyników.

---

## 6. Decyzje, które należą do mnie

**Zakres refaktoru (L4):** AI zaproponowało 10 kandydatów (K1–K10). Wybrałem trzy o najniższym ryzyku i jasnym blast radius (K3, K2, K5). K6 (CapExtended fan-out) i K4 (dual-callsite coordinator) świadomie pominąłem — pierwsze to pytanie domenowe z planu DDD, drugie bo Mutex jest wystarczającym zabezpieczeniem bez nowego koordynatora.

**dagger.Lazy zamiast restrukturyzacji DI (L4 addendum):** Circular dependency można było rozwiązać przez przeprojektowanie modułów Hilt. Wybrałem `dagger.Lazy<AuthApiService>` jako minimalną, odwracalną zmianę — pełna restrukturyzacja DI jest osobną decyzją.

**Kolejność priorytetów DDD (L5):** AI zaproponowało CollectionEntry (#1) i ACL (#2) jako oddzielne zmiany. Zdecydowałem, że CollectionEntry refaktor (4 fazy, test-first, 16 przypadków) trafia do planu jako osobna zmiana — nie do bieżącego sprint'u razem z K3+K2+K5, bo ryzyko i zakres są nieporównywalne.
