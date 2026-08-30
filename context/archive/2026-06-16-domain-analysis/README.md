# Analiza domeny z 2026-06-16 — zamknięta

Trzy dokumenty powstały tego samego dnia jako jedna analiza domenowa. Dwa z nich leżą tutaj,
bo **nie są planem do wykonania** — trzymanie ich w `context/domain/` sprawiało, że katalog
wyglądał jak roadmapa, choć żadna pozycja nie czekała na realizację.

| Dokument | Status |
|---|---|
| `01-domain-distillation.md` | Opis domeny **z czerwca 2026**. Nie był aktualizowany, a kod poszedł dalej: doszedł ręczny wybór producenta dla kapsli „-Multiple countries" (`selected_producer_id`), status `PRODUCER_REMOVED` w `CollectionVerifier`, przebudowa warstwy auth (ReauthInterceptor, ClearanceGate). Czytać jako zapis stanu wiedzy z tamtej daty, nie jako opis dzisiejszego systemu. |
| `02-invariant-aggregate-refactor.md` | Plan agregatu `CollectionEntry` — **świadomie porzucony**. Użytkownik zdecydował nie ruszać tej części; dokument został w repo jako dokumentacja rozważanej opcji (`79f09b5`), nie jako zadanie. Nie wracać bez wyraźnej prośby. |
| `03-anti-corruption-layer.md` | **Wdrożony** — został w `context/domain/`, bo opisuje obowiązujący dziś kształt granicy między Roomem a UI. Kryterium z planu jest spełnione: `grep -r "datasource.local.entity" ui/` daje zero trafień. |
