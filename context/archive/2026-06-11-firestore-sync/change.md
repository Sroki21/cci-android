---
change_id: firestore-sync
title: Firestore sync — backup klaserów/stron/pozycji
status: archived
created: 2026-06-11
updated: 2026-06-15
archived_at: 2026-06-15T08:45:44Z
roadmap_ref: F-03
---

## Notes

Decyzje z sesji planowania:
- Encje: Binder + BinderPage + CapPosition (bez PendingCap)
- Struktura Firestore: 3 płaskie kolekcje users/{uid}/binders, users/{uid}/binder_pages, users/{uid}/cap_positions
- ID strategy: Firestore String ID dodany jako firestoreId: String? do każdej encji Room → Room migration v1→v2
- Auth: Firebase Anonymous Auth (UID persystowany przez Firebase SDK, stały na urządzeniu)
- Fresh install: pull z Firestore do Room przy pierwszym starcie jeśli Room puste
- Konflikty: last-write-wins z polem updatedAt
- Trigger: write-through przy każdym zapisie do Room (Firestore SDK kolejkuje offline)
- Usunięcia: hard delete + ręczna kaskada w Firestore
- Testowanie: Firestore Emulator (testy instrumentowane)
- Fazy: 3 (infra, write-through, restore)
