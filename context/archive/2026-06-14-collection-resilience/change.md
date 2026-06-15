---
change_id: collection-resilience
title: Odporność kolekcji na zmiany w katalogu crowncaps (snapshot + weryfikacja)
status: archived
created: 2026-06-14
updated: 2026-06-15
archived_at: 2026-06-15T00:00:00Z
---

## Notes

**Problem:** kolekcja (klasery → strony → pozycje) trzyma tylko `capId` crowncaps — w Room (`CapPosition.capId`) i w Firestore (`CapPositionDocument {firestoreId, binderPageFirestoreId, position, capId}`). Zdjęcie/kraj/opis dociągane na żywo z API po ID. Zależność 1:1. Ryzyka po stronie crowncaps: usunięcie kapsla (widoczna luka), zmiana ID (luka), **reużycie ID dla innego kapsla (ciche podmienienie danych — najgroźniejsze)**.

**Ustalony kierunek: A + B (dwufazowo).**

**Faza 1 — A: bogatszy snapshot (źródło prawdy do renderowania).**
- Przy wstawianiu kapsla do klasera zapisz niezmienny snapshot: `capId, nazwa, kraj, imageUrl (zawiera hash treści), createdAt, createdBy.id, updatedAt`.
- Trzymaj w Room **i** Firestore (dodać pola do `CapPositionDocument`). Klasery renderuj ze snapshotu, API = doradcze.
- `CapCache (capId → country, image_url)` to istniejący zalążek — poszerzyć o nazwę/fingerprint, uczynić źródłem prawdy, zsynchronizować do Firestore (dziś tylko Room → ginie po reinstalacji).
- Jednorazowy backfill ~4126 zapisów do Firestore (mieści się w 20k/dzień).
- **Wymaga:** dodać `updatedAt` do modelu `CapExtended` (dziś brak; `createdAt`, `createdBy`, `imageUrl` już są).

**Faza 2 — B: weryfikacja (wykrywanie rozjazdu).**
- Brak bulk-endpointu i brak stabilnego klucza (opcja C odpada — tylko numeryczne `id`; nie ma slug/UUID/EAN). Zamiast klucza → **fingerprint**.
- Weryfikacja = 1 GET `/api/v1/caps/{id}` (API publiczne, bez auth, ~2,5 KB). 3 poziomy od najtańszego:
  1. 404 → kapsel usunięty.
  2. `createdAt`/`createdBy.id` ≠ snapshot → **podmiana tożsamości** (czerwony alarm).
  3. `updatedAt` nowsze → zwykła edycja → zaproponuj odświeżenie snapshotu. Jeśli `updatedAt` bez zmian → koniec (grosze).
- **Inkrementalny pasywny (domyślny, konieczny):** ~50 najdawniej weryfikowanych/sesję (pole `lastVerifiedAt`), ogranicznik współbieżności jak w `StatisticsViewModel` (semafor 15, paczki 30), throttle. Pełny obrót kolekcji ~2–4 tyg.
- **Ręczny „Zweryfikuj całość":** throttle ~5 req/s, pasek postępu, anulowanie/wznawianie, sugestia WiFi. Pełny skan 4126 ≈ ~14 min / ~10 MB.
- Wynik jako cichy badge „X rozjazdów do przejrzenia" — bez przerywania; decyzję podejmuje użytkownik.

**Poza zakresem (na później):** WorkManager okresowy (przy inkrementalnym realnie zbędny), własna kopia bajtów zdjęć (opcja D), eksport JSON (E).

**Liczby:** kolekcja = 4126 kapsli (licznik „Kapsle" w Statystykach = `capPositionRepository.getTotalCount()`).

**Zasada przewodnia:** renderuj ze snapshotu, API doradcze, snapshot aktualizuj tylko przez świadomy przepływ weryfikacji — nic się nie podmienia po cichu.
