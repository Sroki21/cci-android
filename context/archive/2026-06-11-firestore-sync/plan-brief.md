# Firestore Sync — Plan Brief

> Full plan: `context/changes/firestore-sync/plan.md`

## What & Why

Lokalna baza Room nie ma backupu — utrata urządzenia = utrata całej mapy fizycznej kolekcji kapsli
(klasery, strony, pozycje). Dodajemy Firebase Firestore jako backup: każdy zapis do Room trafia
jednocześnie do Firestore, a przy nowej instalacji dane są odtwarzane automatycznie.

## Starting Point

F-02 gotowy: Room v1 z 4 tabelami (`binder`, `binder_page`, `cap_position`, `pending_cap`),
3 repozytoriami i modułem Hilt `DatabaseModule`. Projekt nie ma żadnych zależności Firebase.

## Desired End State

Każde create/update/delete Bindera, BinderPage i CapPosition zapisuje dane do Room i Firestore
jednocześnie. Na nowym urządzeniu (lub po czyszczeniu danych): aplikacja startuje, loguje się
anonimowo do Firebase, wykrywa że Room jest puste i odtwarza hierarchię z Firestore w ciągu sekund.

## Key Decisions Made

| Decision | Choice | Why (1 zdanie) | Source |
|---|---|---|---|
| Encje do synchronizacji | Binder + BinderPage + CapPosition | PendingCap to dane przejściowe, mniej krytyczne dla backupu | Plan |
| Struktura Firestore | 3 płaskie kolekcje `users/{uid}/*` | Proste zapytania po ID, łatwa migracja schematu | Plan |
| Strategia ID | Firestore String ID → Room `firestoreId: String?` | Firestore jako source of truth ID; deterministyczne odtwarzanie | Plan |
| Auth | Firebase Anonymous Auth | UID trwały na urządzeniu bez rejestracji konta Firebase | Plan |
| Fresh install | Pull z Firestore przy starcie jeśli Room puste | Główny cel backupu — automatyczne odtworzenie | Plan |
| Konflikty | Last-write-wins z `updatedAt` | Zero UI dla prywatnej aplikacji z jednym użytkownikiem | Plan |
| Trigger sync | Write-through przy każdym zapisie | Najprostszy model — Firestore SDK kolejkuje offline automatycznie | Plan |
| Usunięcia | Hard delete + ręczna kaskada | Firestore nie rośnie bezterminowo; jednoznaczna semantyka | Plan |
| Testowanie | Firestore Emulator (instrumentowane) | Realne zachowanie Firestore bez kosztów produkcji | Plan |

## Scope

**In scope:**
- Firebase SDK (Firestore + Auth) w `app/build.gradle`
- Room migration v1→v2: kolumna `firestore_id` w 3 tabelach
- `FirebaseAuthManager` — anonymous sign-in, `StateFlow<String?> uid`
- `FirestoreModule` — Hilt providers dla FirebaseAuth + FirebaseFirestore
- 3 klasy `*FirestoreService` (Binder, BinderPage, CapPosition)
- Write-through w istniejących 3 repozytoriach
- `FirestoreRestoreUseCase` — restore przy starcie
- `CCIApplication` — trigger auth + restore na `onCreate()`

**Out of scope:**
- Sync PendingCap
- Real-time listeners (Firestore → Room update)
- Firebase Email Auth / link kont
- Backfill istniejących danych Room bez `firestoreId`
- Security rules w Firestore Console (konfiguracja manualna)

## Architecture / Approach

```
CCIApplication.onCreate()
  └── FirebaseAuthManager.ensureSignedIn()   [anonymous auth → uid: StateFlow<String?>]
  └── FirestoreRestoreUseCase.restoreIfEmpty()  [jeśli Room puste: Firestore → Room]

BinderRepository.create(name)
  ├── BinderFirestoreService.scheduleCreate(uid, name)  → firestoreId (UUID, działa offline)
  └── BinderDao.insert(Binder(name, firestoreId))

Firestore kolekcje:
  users/{uid}/binders/{firestoreId}           { name, updatedAt }
  users/{uid}/binder_pages/{firestoreId}      { binderFirestoreId, pageNumber, updatedAt }
  users/{uid}/cap_positions/{firestoreId}     { binderPageFirestoreId, position, capId, updatedAt }
```

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Firebase infra + Room migration | SDK, Anonymous Auth, `firestoreId` w encjach, migracja v1→v2 | Wymaga manualnego prerequisite: Firebase projekt + google-services.json |
| 2. Write-through sync | Każdy zapis do Room → Firestore; `firestoreId` persystowany | Kaskadowe usunięcia w Firestore muszą być ręczne (brak FK w Firestore) |
| 3. Initial pull / restore | Odtworzenie Room z Firestore na nowym urządzeniu | Poprawne wiązanie relacji przez firestoreId→roomId mapę |

**Prerequisites:**
- F-02 zaimplementowany ✓
- Firebase projekt stworzony w Firebase Console (MANUALNIE przed Phase 1)
- `google-services.json` umieszczony w `app/`
- Firebase Console: Firestore + Anonymous Auth włączone

**Estimated effort:** ~3 sesje implementacji (3 fazy)

## Open Risks & Assumptions

- **Backfill brak**: dane Room sprzed F-03 (`firestoreId = NULL`) nie zostaną zsynchronizowane do Firestore — użytkownik musi ręcznie edytować lub zaakceptować brak backupu dla starych danych
- **Emulator CI**: `FirestoreWriteThroughTest` i `FirestoreRestoreTest` wymagają Firebase Local Emulator Suite (Node.js + Firebase CLI) — może nie działać w CI bez konfiguracji
- **Anonymous UID i nowe urządzenie**: nowe urządzenie = nowy UID → inne dokumenty Firestore. Przeniesienie danych między urządzeniami wymaga link kont Firebase (poza scope MVP)

## Success Criteria (Summary)

- Utwórz klaser → pojawia się w Firebase Console pod `users/{uid}/binders`
- Wyczyść dane aplikacji → uruchom ponownie → klaser i strony odtworzone w Room (App Inspection)
- Brak crasha ani ANR podczas normalnego użycia z/bez sieci
