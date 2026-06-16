## Code Review Criteria

Each criterion is scored on a 1–10 scale, where 1 is the worst outcome and 10 is the best.

1) implementation correctness
   - logic matches PR description; null safety and edge cases handled
   - dual-write pattern respected: write to Room AND Firestore; always read from Room
   - Room migrations not skipped; autoMigrations or migration file present
   - suspend functions and coroutine scopes correct (no GlobalScope, cancellation handled)

2) architectural compliance
   - Composable = render + event propagation only; no business logic
   - ViewModel = StateFlow/Flow; no direct Retrofit or Room DAO imports
   - Repository = data source coordination; no UI knowledge
   - new @HiltViewModel with @Inject constructor; new API services as @Singleton in NetworkModule
   - models: domain models in model/, internal models in data/model/

3) kotlin / compose idiomaticity
   - state hoisting respected; no state in Composable beyond remember {}
   - Flow collected via collectAsStateWithLifecycle() (not collectAsState())
   - no unnecessary !! or unsafe as? casts where Kotlin offers better tools
   - new Composables accept data as parameters — do not inject ViewModel internally

4) test coverage proportional to risk
   - ViewModel and Repository: unit test with MockK + runTest
   - new PagingSource: test created via Repository (not ViewModel)
   - new critical paths (auth, Firestore sync): at minimum happy path + one error case
   - Composable JVM tests not required; instrumented tests optional

5) security and data safety
   - no hardcoded credentials, API keys, or tokens in code or strings
   - no Context/Activity leak in ViewModel (only ApplicationContext via Hilt)
   - destructive DB operations (DROP, DELETE *) require explicit justification comment
   - if PR changes Firestore document structure, verify alignment with Security Rules
