# Odporność kolekcji na zmiany w katalogu crowncaps — Plan Brief

> Full plan: `context/changes/collection-resilience/plan.md`

## What & Why

Kolekcja zależy dziś 1:1 od crowncaps.info — pozycja w klaserze trzyma tylko `capId`, a wszystko inne dociągane jest z API. Gdy crowncaps usunie kapsel, zmieni jego ID lub (najgroźniejsze) przypisze ID do innego kapsla, kolekcja po cichu się rozjeżdża. Budujemy trwały snapshot, z którego renderuje się kolekcja, plus silnik weryfikacji wykrywający rozjazdy przez „fingerprint".

## Starting Point

Snapshot częściowo już istnieje: `cap_cache (cap_id, country, image_url)` w Room, z którego renderują się klasery (API tylko dla braków). Ale jest ubogi (bez nazwy i fingerprintu) i **wyłącznie lokalny** — ginie po reinstalacji i odtwarza się z API. Dodawanie kapsla (`CapDetailViewModel`) ma już pod ręką pełny `CapExtended`, więc przechwycenie fingerprintu jest darmowe.

## Desired End State

Każda pozycja ma trwały snapshot (nazwa, kraj, zdjęcie, `createdAt`, `createdBy.id`, `updatedAt`) w Room i Firestore; kolekcja renderuje się offline i przeżywa reinstalację. Silnik weryfikacji rozpoznaje usunięcie / podmianę tożsamości / edycję i zgłasza je w menu konta (odznaka + ekran przeglądu) **oraz w Klaserach** — oflagowany kapsel, jego strona i klaser są pisane czerwoną, pogrubioną czcionką, a decyzję (zachowaj snapshot / zaakceptuj nowy / odepnij) podejmujesz z poziomu szczegółów kapsla.

## Key Decisions Made

| Decyzja | Wybór | Dlaczego | Źródło |
| --- | --- | --- | --- |
| Klucz alternatywny (opcja C) | Brak — używamy fingerprintu | API ma tylko numeryczne `id`; `createdAt`+`createdBy`+`updatedAt`+hash zdjęcia zastępują klucz | Plan (rekonesans API) |
| Snapshot w Firestore | Rozszerzyć `CapPositionDocument` | Jedzie na gotowym sync+restore; kapsel=pozycja 1:1 | Plan |
| Backfill 4126 kapsli | Automat w tle po aktualizacji | Zero akcji użytkownika, throttle+wznawianie | Plan |
| Podmiana tożsamości | Flaga + akcje ręczne | Pełna kontrola, nic nie zmienia się po cichu | Plan |
| Kapsel usunięty (404) | Zostaw pozycję + oznacz | Zero utraty danych | Plan |
| UI rozjazdów + odznaka | Menu konta **+ Klasery** (czerwona pogrubiona czcionka, propagacja kapsel→strona→klaser); decyzja w szczegółach kapsla | Widoczność podczas zwykłego przeglądania, nie tylko w menu konta | Plan |
| Bieżąca weryfikacja | Pasywnie ~50/sesję + ręczny pełny skan | Prawie darmowe, samonaprawialne; WorkManager odłożony | Plan |

## Scope

**In scope:** snapshot (nazwa+fingerprint) w Room+Firestore; przechwycenie przy dodawaniu; render ze snapshotu; silnik weryfikacji 3-poziomowy (backfill/inkrement/ręczny); przegląd rozjazdów z akcjami; odznaka w menu konta.

**Out of scope:** filtr roku; własna kopia bajtów zdjęć (opcja D); WorkManager okresowy; eksport JSON; kraje historyczne.

## Architecture / Approach

Snapshot = poszerzony `cap_cache` (Room v6→7, `ALTER TABLE ADD COLUMN`) + nowe pola w `CapPositionDocument` (Firestore), odtwarzane przez `FirestoreRestoreUseCase`. Render klaserów już czyta `cap_cache` — wzbogacamy o nazwę i czynimy autorytatywnym. Weryfikacja = jeden rdzeń `CollectionVerifier` (`verify(capId)`: GET detalu → porównaj fingerprint → status, zapis do Room i Firestore), napędzany trzema trybami dzielącymi wspólny throttle i wznawialnymi po `last_verified_at`.

## Phases at a Glance

| Faza | Co dostarcza | Główne ryzyko |
| --- | --- | --- |
| 1. Snapshot (A) | Trwały snapshot + sync/restore + render offline | Migracja Room + tolerancja starych dokumentów Firestore |
| 2. Weryfikacja (B) + przegląd | Silnik 3-trybowy + ekran rozjazdów + odznaka | Throttle/uprzejmość wobec crowncaps; poprawność wykrywania podmiany |

**Prerequisites:** brak — buduje na istniejącym Room/Firestore/sync.
**Estimated effort:** ~3–4 sesje (Faza 1 mniejsza, Faza 2 większa).

## Open Risks & Assumptions

- Założenie: kapsel = jedna pozycja (unassign/reassign trzyma pojedynczą) — uzasadnia snapshot per-pozycja w Firestore.
- URL-e zdjęć (CloudFront) mogą wygasać — fingerprint to wykryje (inny hash), ale plik może być nieosiągalny; kopia bajtów (D) świadomie odłożona.
- Backfill 4126 obciąża serwer crowncaps jednorazowo — łagodzone throttlingiem i preferencją unmetered.

## Success Criteria (Summary)

- Po reinstalacji + restore kolekcja renderuje się w całości offline (nazwy + zdjęcia).
- Sztucznie wywołany rozjazd (podmiana/usunięcie) pojawia się w przeglądzie z właściwym statusem.
- Akcje zachowaj / zaakceptuj / odepnij działają i aktualizują odznakę; tempo zapytań grzecznościowe.
