---
id: auth-scaffold
title: Auth scaffold — logowanie kontem crowncaps.info
status: implemented
created: 2026-06-11
updated: 2026-06-11
roadmap_ref: F-01
---

Fundament autentykacji: OkHttp CookieJar z persystencją (Laravel Sanctum cookie mode),
interceptor CSRF, AuthRepository (login/logout/currentUser), LoginScreen + LoginViewModel.
Odblokuje S-01 (shop-check-and-mark-bought).
