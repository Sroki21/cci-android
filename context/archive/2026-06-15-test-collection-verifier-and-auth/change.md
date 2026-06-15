---
id: test-collection-verifier-and-auth
title: "Phase A — Data Integrity: CollectionVerifier + Auth tests"
status: archived
archived_at: 2026-06-15T14:26:25Z
created: 2026-06-15
updated: 2026-06-15

roadmap_ref: test-plan Phase A (R1, R3, R6)
---

# test-collection-verifier-and-auth

Phase A z `context/foundation/test-plan.md`.

Dodaje testy jednostkowe JVM (mockk + runTest) dla trzech obszarów bez pokrycia:
- **R1**: CollectionVerifier.verify() — wszystkie 5 statusów CatalogStatus
- **R3**: FirebaseAuthManager.signInWithEmail — fallback createUser na dowolnym wyjątku
- **R6**: init AuthRepository z token != null bez cookie → isLoggedIn=true; SessionAuthenticator clears session on 401
