# Research — FirestoreRestoreUseCase: flow restore kolekcji po logowaniu

**Cel:** Badam flow restore kolekcji po zalogowaniu — od `CCIApplication.onCreate()` i `AuthRepository.login()` przez `FirestoreRestoreUseCase.restoreIfEmpty()` do serwisów Firestore i DAO Room — bo mapa wskazała ten obszar jako jedyną destruktywną operację w systemie, wywoływaną z dwóch miejsc, chronioną Mutexem, z warstwą Firestore całkowicie poza statyczną analizą importów (`unknown`).

---
