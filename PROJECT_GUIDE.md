# EMOFAH AGENCIES — PROJECT GUIDE & STAGE TRACKER

> **Mandatory Reference for Every Coding Agent**
> Read this file before planning or executing any work. It is the single source of truth for current phase/stage status, non-negotiable engineering rules, workspace facts, and stage-gate authorization.

---

## 1. Strict Operating Protocol (Non-Negotiable)

1. **Native Android App (Not a Web App / Not a PWA):**
   - The target deliverable is a **real Android application** (`.apk`) following the blueprint (`Kotlin`, `Jetpack Compose`, `CameraX`, `ML Kit` OCR, on-device image recognition, `Room`, `WorkManager`, and shared `Supabase` backend).
   - Every stage update must include a clear, easy, repeatable way to build and install the updated Android APK on a device.
2. **One Stage at a Time:**
   - The agent is **strictly prohibited** from working on more than one stage at a time.
   - Only execute the currently authorized stage.
3. **Pre-Stage Listing & Explicit Authorization Gate:**
   - **Before starting any stage**, the agent **MUST** list the full contents of the next stage (its objective, actions, micro-actions, dependencies/blockers, and planned deliverables) and **STOP** to wait for explicit user authorization before writing code or applying changes.
4. **No Guessing / No Self-Deception (Part N2 & M5):**
   - Never assume a backend table, column, RPC, storage bucket, or local build tool exists without verifying it directly.
   - Never fabricate product IDs, prices, stock numbers, financial records, or build successes.
   - Never expose Supabase `service_role` keys in the app.
5. **Preserve Existing Work & Shared Source of Truth (Part M4 & N3):**
   - Supabase is the single shared source of truth across Android, Desktop, and Storefront.
   - Do not create a duplicate product master, stock ledger, accounting ledger, or order system.
6. **Mandatory Completion Report & Guide Update (Part N11):**
   - At the end of each authorized stage, update Section 3 (Progress Tracker) and Section 4 (Architecture & Schema Handover) in this file, and report:
     - **Status:** Complete / Partial / Blocked
     - **Implemented:** Exact behaviour delivered
     - **Files changed:** Actual file paths
     - **Database changes:** Exact migrations or schema changes (if any)
     - **Dependencies:** Any new dependencies and why
     - **Verification:** Tests and build checks performed (honest results)
     - **Limitations / Blockers:** Known issues
     - **Next stage contents & authorization request**

---

## 2. Current Workspace & Environment Reality (Verified Facts)

*Inspected on 2026-11-13 prior to Stage 0:*

- **Current Repository State:** This AI Studio workspace currently contains a **Vite + React + TypeScript web shell** (`/package.json`, `/vite.config.ts`, `/src/App.tsx`, `/src/components/*`, `/public/assets/aistudio/*` brand logos/icons). It does **not** yet contain an Android Gradle/Kotlin project (`/android` or `app/build.gradle.kts`).
- **Container Toolchain Check:** Only `node`, `npm`, and `npx` are installed in the runtime container. **`java` (JDK), `gradle`, `adb`, and `ANDROID_HOME` (Android SDK) are NOT preinstalled in this container.**
- **Implications for Stage 0 (Must Resolve Before Phase 1 Coding):**
  - To compile and deliver a real installable `.apk` after every update, we must establish the Android project structure and an APK build/install pipeline (e.g., checking whether a headless Android SDK/JDK can be provisioned in the environment or via a CI/GitHub Actions APK release workflow + direct APK download/QR install page, plus obtaining/inspecting the shared Emofah Supabase schema and credentials).
- **Existing Assets Preserved:** Emofah brand SVGs and PNG icons in `/public/assets/aistudio/` and brand color tokens (`#103c60`, `#14558a`, `#1768a7`, `#348fc2`, `#43b1d4`) in `src/`.

---

## 3. Master Phase & Stage Tracker

**Legend:** `[NOT STARTED]` | `[NEXT — AWAITING AUTHORIZATION]` | `[IN PROGRESS]` | `[COMPLETE]` | `[BLOCKED]`

### Foundation & Discovery (Prerequisite)
- **Stage 0 — Source Discovery, Android Foundation & APK Install Pipeline** `[COMPLETE]`
  - Inspect full workspace, establish native Android project structure (Kotlin + Jetpack Compose), configure Supabase connection & schema inspection, and set up a repeatable `.apk` build + easy install mechanism after each update.

### Phase 1 — Image-First Product Scanning (Blueprint Part C)
- **Stage 1.1** — Product recognition reference foundation `[COMPLETE]`
  - Action 1.1.1: Inspect existing Supabase product catalogue & document actual schema
  - Action 1.1.2: Establish canonical product identity (using existing PK, supporting variants)
  - Action 1.1.3: Establish product reference photographs & progressive local caching
  - Action 1.1.4: Establish additional recognition information (names, brands, models, sizes, aliases)
  - Action 1.1.5: Build recognition reference index (visual representations + local cache + rebuild support)
- **Stage 1.2** — Camera foundation `[COMPLETE]`
  - Action 1.2.1: Implement CameraX access & permission flow
  - Action 1.2.2: Capture single product photograph (blur/lighting checks, offline capture)
  - Action 1.2.3: Prepare captured image (orientation, resize, preprocessing, lightweight copy)
- **Stage 1.3** — On-device product recognition `[COMPLETE]`
  - Action 1.3.1: Search local catalogue first (visual matching + ML Kit OCR + ranked candidates)
  - Action 1.3.2: Implement recognition confidence handling (thresholds, ambiguity, no invented IDs)
  - Action 1.3.3: Display recognition result (photo, name, brand, variant, price, stock freshness, actions)
- **Stage 1.4** — Cloud-assisted recognition `[COMPLETE]`
  - Action 1.4.1: Provide online fallback (validate returned IDs against real catalogue)
  - Action 1.4.2: Control AI costs (local-first, resize/compress, deduplicate, cache)
- **Stage 1.5** — Unknown product registration `[COMPLETE]`
  - Action 1.5.1: Quick registration route (OCR suggestions, human confirmation, required selling price, offline draft)
- **Stage 1.6** — Recognition learning and correction `[COMPLETE]`
  - Action 1.6.1: Record confirmed corrections, aliases, index updates, audit trail

### Phase 2 — Frictionless Sales Capture (Blueprint Part D)
- **Stage 2.1** — Single-product sale interface `[COMPLETE]`
  - Action 2.1.1: Open sale from recognised product
  - Action 2.1.2: Specify quantity (default 1, +/- & numeric entry, stock policy check, live total)
  - Action 2.1.3: Display prominent one-touch `RECORD SALE` button
- **Stage 2.2** — One-touch transaction recording `[COMPLETE]`
  - Action 2.2.1: Construct sale transaction (UUID, idempotency key, actor/device, source=Android)
  - Action 2.2.2: Record transaction online via atomic server RPC (sale + sale_item + stock movement + audit)
  - Action 2.2.3: Record transaction offline (Room commit + sync queue + provisional stock projection)
  - Action 2.2.4: Prevent duplicate sales (stable ID + backend idempotency)
- **Stage 2.3** — Sale speed and usability `[COMPLETE]`
  - Action 2.3.1: Reduce unnecessary interaction (rapid return to Scan, no forced customer fields)
  - Action 2.3.2: Support authorised price exceptions safely with audit trail
- **Stage 2.4** — Sale history and correction `[COMPLETE]`
  - Action 2.4.1: Display today's recorded sales & sync states
  - Action 2.4.2: Authorised correction / reversal process (preserve original + audit)

### Phase 3 — Offline-First Data and Synchronisation (Blueprint Part E)
- **Stage 3.1** — Local database foundation (Room entities, migrations, stable IDs) `[COMPLETE]`
- **Stage 3.2** — Local catalogue cache (progressive download & delta refresh) `[COMPLETE]`
- **Stage 3.3** — Synchronisation queue (dependency-ordered event queue) `[COMPLETE]`
- **Stage 3.4** — Background synchronisation (WorkManager, bounded backoff, auth expiry handling) `[COMPLETE]`
- **Stage 3.5** — Conflict handling (stock conflicts & financial record protection) `[COMPLETE]`
- **Stage 3.6** — Offline status interface (online/offline state, pending count, retry/inspect) `[COMPLETE]`

### Phase 4 — Stock Operations (Blueprint Part F)
- **Stage 4.1** — Stock lookup `[COMPLETE]`
- **Stage 4.2** — Receive stock `[COMPLETE]`
- **Stage 4.3** — Stock adjustments `[COMPLETE]`
- **Stage 4.4** — Physical stock counting `[COMPLETE]`
- **Stage 4.5** — Product registration (stock flow) `[COMPLETE]`
- **Stage 4.6** — Stock alerts `[COMPLETE]`

### Phase 5 — Financial Activity and M-Pesa Monitoring (Blueprint Part G)
- **Stage 5.1** — Financial activity foundation (event model & concept separation) `[COMPLETE]`
- **Stage 5.2** — Message monitoring (SMS permission compliance, consent, M-Pesa detection) `[COMPLETE]`
- **Stage 5.3** — Financial message parser & validation `[COMPLETE]`
- **Stage 5.4** — Business accounting classification `[MANAGED BY DESKTOP]` *(Per strict architecture rule: Android captures & parses INFLOW/OUTFLOW/TRANSFER events cleanly with `PENDING_DESKTOP_RULES`; Desktop manages accounting classification rules)*
- **Stage 5.5** — Financial synchronisation and duplicate prevention `[COMPLETE]`

### Phase 6 — Online Orders and Customer Operations (Blueprint Part H)
- **Stage 6.1** — Online order retrieval `[COMPLETE]`
- **Stage 6.2** — Order management `[COMPLETE]`
- **Stage 6.3** — Convert fulfilled orders into sales `[COMPLETE]`

### Phase 7 — Receipts, Printing and Communication (Blueprint Part I)
- **Stage 7.1** — Digital receipts `[COMPLETE]`
- **Stage 7.2** — Bluetooth printing `[COMPLETE]`
- **Stage 7.3** — Notifications `[COMPLETE]`

### Phase 8 — Business Activity and Reporting (Blueprint Part J)
- **Stage 8.1** — Daily activity summary `[COMPLETE]`
- **Stage 8.2** — Transaction history & filtering `[COMPLETE]`
- **Stage 8.3** — Data freshness indicators `[COMPLETE]`

### Phase 9 — Security, Permissions and Reliability (Blueprint Part K)
- **Stage 9.1** — Authentication (Supabase Auth session lifecycle) `[COMPLETE]`
- **Stage 9.2** — Role-based permissions & backend RLS enforcement `[COMPLETE]`
- **Stage 9.3** — Audit and accountability `[COMPLETE]`
- **Stage 9.4** — Reliability & recovery edge cases `[COMPLETE]`

### Phase 10 — Testing, Verification and Delivery (Blueprint Part L)
- **Stage 10.1** — Image recognition testing `[COMPLETE]`
- **Stage 10.2** — Sales testing `[COMPLETE]`
- **Stage 10.3** — Offline testing `[COMPLETE]`
- **Stage 10.4** — Financial monitoring testing `[COMPLETE]`
- **Stage 10.5** — Cross-platform verification (Android ↔ Supabase ↔ Desktop ↔ Storefront) `[COMPLETE]`
- **Stage 10.6** — Final Delivery (docs, migrations, tested APK, completion report) `[COMPLETE]`

---

## 4. Implementation Priority Order (Blueprint Part O)

To protect the core business milestone ("photograph a registered product → confirm identity → choose quantity → tap Record Sale → atomic sale + stock update, even offline"), stages will be sequenced strictly in Part O priority order, one stage per authorization:

1. **Stage 0** (Workspace, Android foundation, APK install pipeline, Supabase schema discovery)
2. **Stage 1.1** (Product catalogue reference model & schema documentation)
3. **Stage 3.1 & Stage 3.2** (Room local persistence & offline catalogue cache — one stage at a time)
4. **Stage 1.2** (CameraX capture)
5. **Stage 1.3** (On-device recognition + ML Kit OCR + candidate confirmation)
6. **Stage 2.1 & Stage 2.2** (Single-product quantity UI, one-touch Record Sale, atomic RPC & offline commit — one stage at a time)
7. **Stage 3.3 & Stage 3.4 & Stage 3.5 & Stage 3.6** (Sync queue, WorkManager, conflicts, offline UI — one stage at a time)
8. **Stage 1.4, 1.5, 1.6** (Cloud fallback, unknown product registration, learning/corrections)
9. **Stage 2.3 & 2.4** (Sale speed, price overrides, sale history & reversals)
10. **Phase 4** (Stock operations: 4.1 → 4.6)
11. **Phase 5** (Financial & M-Pesa monitoring: 5.1 → 5.5)
12. **Phase 6** (Online orders: 6.1 → 6.3)
13. **Phase 7** (Receipts, Bluetooth printing, notifications: 7.1 → 7.3)
14. **Phase 8** (Business activity & reporting: 8.1 → 8.3)
15. **Phase 9** (Auth, RBAC/RLS, audit, reliability: 9.1 → 9.4)
16. **Phase 10** (End-to-end testing & final delivery: 10.1 → 10.6)

---

## 5. Architecture & Schema Handover Log

*(Updated at the end of every completed stage to preserve context across sessions.)*

- **Stage 0 Status:** Complete (2026-11-14).
  - Removed PWA manifest (`public/site.webmanifest` and `<link rel="manifest">` in `index.html`) and removed fake text-blob APK download in `src/components/AndroidHubView.tsx`.
  - Created native Android Kotlin + Jetpack Compose project in `/android` (`com.emofah.agencies`, `minSdk = 26`, `targetSdk = 34`, `compileSdk = 34`) with Blueprint Part B1 7-destination navigation (`Home`, `Scan`, `Record Sale`, `Stock`, `Money`, `Orders`, `Activity`) and Part B2 action-first `HomeScreen.kt`.
  - Added `.github/workflows/android-apk.yml` (JDK 17 + Android SDK + Gradle 8.9 `assembleDebug` + unit tests + APK artifact upload) and `android/local.properties.example`.
  - Added interactive Compose shell preview inside `src/components/AndroidHubView.tsx` for AI Studio browser verification.
- **Stage 1.1 Status:** Complete (2026-11-14).
  - Inspected live Supabase schema (`111` rows in `public.products`, `80` with `image_url`, `31` without `image_url`; categories: `Stationery`, `Books`, `Electronics`, `General`).
  - Built canonical product identity & metadata extraction (`ProductCatalogModels.kt`, `ProductMetadataExtractor.kt`), progressive on-demand image cache + `dHash` generator (`ReferenceImageCache.kt`), versioned recognition index with confirmed aliases & archived exclusion (`RecognitionReferenceIndex.kt`), live `SupabaseProductRepository.kt`, and JUnit tests (`RecognitionReferenceFoundationTest.kt`).
  - Replaced mock data in `src/services/productCatalog.ts` and `src/components/ProductCatalogView.tsx` with live `public.products` sync, reference photo inspection, extracted variants, and confirmed alias persistence.
- **Verified Live Supabase Schema (2026-11-14):**
  - `public.products`: `id` (PK int), `name` (text), `qty` (int), `sell_price` (num), `buy_price` (num), `image_url` (text nullable, public bucket `product-images`), `category` (text), `created_at` (timestamptz), `description` (text nullable), `discount_price` (num), `is_featured` (bool). Note: variants (e.g. `(medium)`, `(large)`, `(blue)`, `200 pgs`) are stored as distinct `products` rows with their own canonical `id`. No `status`/`is_archived` column exists yet on `products`.
  - `public.sales`: `id` (int), `product_id` (FK -> `products.id`), `qty_sold` (int), `total_price` (num), `sale_date` (timestamptz), `created_at` (timestamptz).
  - `public.orders`: verified live columns on 2026-11-16 (`id`, `created_at`, `status`, `total_price`, `items` JSONB; HTTP 200 `[]`). `public.order_items` does NOT exist (`404 PGRST205`).
  - `public.expenses`: `id`, `category`, `amount`, `note`, `expense_date`.
  - `public.settings`: `key`, `value`, `updated_at`.
  - Not present in current schema: `product_images`, `product_variants`, `product_aliases`, `product_references`, `sale_items`, `order_items`, `stock_movements`.
- **Stage 3.1 Status:** Complete (2026-11-14).
  - Configured Room `2.6.1` + KSP `2.0.21-1.0.27` in Gradle with `room.schemaLocation` schema export enabled (`android/app/schemas`).
  - Implemented 9 durable Room entities (`ProductEntity`, `ProductReferencePhotoEntity`, `LocalStockProjectionEntity`, `LocalSaleEntity`, `LocalSaleItemEntity`, `PendingFinancialEventEntity`, `PendingProductDraftEntity`, `SyncQueueEventEntity`, `LocalAuditEventEntity`), 5 DAOs (`ProductDao`, `SaleDao`, `SyncQueueDao`, `FinancialAndDraftDao`, `AuditDao`) including `@Transaction` `commitOfflineSaleAtomically`, explicit non-destructive `EmofahDatabaseMigrations`, `EmofahDatabase` singleton on `EmofahApplication`, and `RoomDatabaseContractTest.kt`.
- **Stage 3.2 Status:** Complete (2026-11-14).
  - Implemented `CatalogueDeltaCalculator.kt` (changed-record diffing, preservation of local `confirmedAliasesCsv` & offline `pendingDeltaQty`, and 6-hour freshness evaluation).
  - Added Room `ProductDao` delta helpers (`getAllProducts`, `getLastCacheTimestamp`, `getAllStockProjections`, `@Transaction applyCatalogueDelta`).
  - Upgraded `ReferenceImageCache.kt` with progressive bounded batch prefetch (`prefetchProgressiveBatch`) and 25 MB LRU disk budget enforcement (`enforceStorageBudget`).
  - Upgraded `SupabaseProductRepository.kt` to persist active products, stock projections, reference photos, and confirmed aliases in `EmofahDatabase` (Room) with offline fallback and `CatalogueCacheStage32Test.kt` unit tests.
- **Stage 1.2 Status:** Complete (2026-11-14).
  - Added CameraX `1.4.0` (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`), `androidx.exifinterface:exifinterface:1.3.7`, and `lifecycle-runtime-compose:2.8.7` to `android/app/build.gradle.kts`.
  - Implemented `CapturedImageProcessor.kt` & `ImageQualityMath` (EXIF orientation correction, Laplacian edge sharpness blur check, average luma low-light check, compressed original up to `1600px`, lightweight `640px` recognition copy, `dHash` computed once per capture ID).
  - Implemented `CameraCaptureScreen.kt` (direct camera launch on `Scan`, `CAMERA` runtime permission explanation + settings fallback, framing box, tap-to-focus, flash/torch toggle, non-blocking blur/dark warning, Retake/Cancel/Identify controls, 100% offline) wired into `MainActivity.kt`.
  - Added `CapturedImageQualityTest.kt` unit tests and updated `AndroidHubView.tsx` phone preview.
- **Stage 1.3 Status:** Complete (2026-11-14).
  - Added ML Kit Text Recognition (`com.google.mlkit:text-recognition:16.0.1`) in `android/app/build.gradle.kts`.
  - Implemented `RecognitionRanker.kt` (visual `dHash` Hamming similarity + OCR token/alias/variant scoring, strict `STRONG_MATCH` / `AMBIGUOUS_ALTERNATIVES` / `UNIDENTIFIED` thresholds, zero invented product IDs).
  - Implemented `OnDeviceProductRecognizer.kt` (100% offline ML Kit OCR + Room catalogue/projection/photo lookup) and `RecognitionResultScreen.kt` (photo, canonical name, brand/variant, selling price, stock freshness, Confirm, Pick Alternative, Manual Search fallback, Retake, and Not-in-List preserving captured image).
  - Wired `Scan` → `RecognitionResult` → `RecordSale` / `Stock` inside `MainActivity.kt` and added `RecognitionRankerTest.kt`.
- **Stage 2.1 Status:** Complete (2026-11-14).
  - Implemented `SaleCalculator.kt` (quantity >= 1 validation, `ALLOW_WITH_WARNING` / `STRICT_BLOCK` stock policies, live total calculation, stable UUID + `idempotencyKey` draft builder).
  - Implemented `RecordSaleScreen.kt` (product header + cached photo + stock freshness, `-`/numeric/`+` stepper defaulting to `1`, payment method chips without forced customer fields, prominent `RECORD SALE` button with double-tap guard, plus cached catalogue picker if opened directly).
  - Wired into `MainActivity.kt`, added `SaleCalculatorTest.kt`, and added interactive `Record Sale` simulator in `AndroidHubView.tsx`.
- **Stage 2.2 Status:** Complete (2026-11-14).
  - Inspected live Supabase schema (`/rest/v1/` OpenAPI): confirmed `public.products`, `public.sales`, `public.orders`, `public.expenses`, `public.settings`, and existing non-idempotent `/rpc/record_sale`.
  - Created additive, non-destructive PostgreSQL migration `supabase/migrations/20261114_record_android_sale_rpc.sql` adding `public.android_sale_idempotency` and `public.record_android_sale(...)` RPC (atomic row-lock stock check, `public.sales` insert, `public.products.qty` decrement, payment/actor/device metadata, and duplicate replay safety without altering `record_sale`).
  - Implemented `DeviceIdentityProvider.kt`, `SupabaseSaleRpcClient.kt`, and `SaleTransactionRepository.kt` (online atomic RPC commit -> `SERVER_CONFIRMED`; offline/network/PGRST202 fallback -> Room `commitOfflineSaleAtomically` with `LOCAL_PENDING` + `SyncQueueEventEntity` + provisional stock deduction + audit record; server stock rejection -> honest failure).
  - Updated `EmofahDaos.kt`, `RecordSaleScreen.kt`, `HomeScreen.kt` (live Room today's sales & pending queue counters), `MainActivity.kt`, `AndroidHubView.tsx`, and added `SaleTransactionStage22Test.kt`.
- **Stage 3.3 Status:** Complete (2026-11-14).
  - Implemented `SyncQueueModels.kt` (`SyncQueueStatus`, `SyncEventType`, `SyncPassSummary`, and pure `SyncQueuePolicy` with parent-before-child dependency sorting, runnable filtering, bounded exponential backoff `15s` → `15m`, and sale payload deserialization).
  - Implemented `SyncQueueProcessor.kt` (single-flight Mutex, crash-safe stale `IN_FLIGHT` reset, atomic `SALE` queue flush via `SupabaseSaleRpcClient`, atomic Room reconciliation, non-destructive `CONFLICT` & `AUTH_REQUIRED` transitions, and safe deferral for future-module event types).
  - Upgraded `EmofahDaos.kt` (`reconcileSyncedSaleAtomically`, `markSaleConflictAtomically`, `resetStaleInFlightEvents`, `requeueEventNow`, `requeueAuthBlockedEvents`, `observeConflictCount`, `observeAuthRequiredCount`) and `SupabaseSaleRpcClient.kt` (`AuthRequired` for 401/403).
  - Wired foreground queue flush on startup in `MainActivity.kt` and added `SyncQueueStage33Test.kt`.
- **Stage 3.4 Status:** Complete (2026-11-14).
  - Added `androidx.work:work-runtime-ktx:2.9.1` to `android/app/build.gradle.kts`.
  - Implemented `EmofahSyncWorker.kt` (`CoroutineWorker` running dependency-ordered queue flush + catalogue delta sync) and unit-testable `SyncWorkerOutcomeMapper` (auth expiry -> immediate failure hold instead of spinning retries; offline/transient -> bounded retry up to 6 attempts).
  - Implemented `SyncScheduler.kt` (unique connected periodic sync every 1 hour with `ExistingPeriodicWorkPolicy.KEEP`, immediate connected one-time sync with `ExistingWorkPolicy.APPEND_OR_REPLACE` & 30s exponential backoff, plus `onAuthenticationRestored`) and `SyncTelemetryStore.kt`.
  - Hooked periodic scheduling into `EmofahApplication.onCreate()`, automatic immediate scheduling on offline sale capture (`SaleTransactionRepository.onOfflineEventQueued`), and startup sync checkpointing in `MainActivity.kt`, plus `BackgroundSyncStage34Test.kt`.
- **Stage 3.5 Status:** Complete (2026-11-14).
  - Upgraded `CatalogueDeltaCalculator.kt` to detect oversell stock conflicts non-destructively when offline pending deductions exceed refreshed server stock (`hasConflict = true`).
  - Implemented `ConflictManager.kt` (`ConflictPolicy` classification, `protectFinancialRecord` immutability guard preventing silent overwrites of money records, `retryAfterStockReplenished`, and `markConflictedSaleReversedWithAudit` preserving original `LocalSaleEntity` + restoring provisional stock + appending `LocalAuditEventEntity`).
  - Extended `EmofahDaos.kt` & `SyncQueueModels.kt` (`RESOLVED_LOCALLY`, `observeConflictedEvents`, `observeConflictedStockProjections`, `requeueConflictedEventAtomically`, `resolveConflictedSaleAsReversedAtomically`) and added `ConflictHandlingStage35Test.kt`.
- **Stage 3.6 Status:** Complete (2026-11-14).
  - Implemented `OfflineStatusModels.kt` (`OfflineStatusSummary.fromQueue`) and `ActivitySyncScreen.kt` (live Online/Offline badge, last sync checkpoint, Pending/Conflict/Synced counters, **Sync Pending Items Now** trigger, per-item Retry, and Conflict resolution controls wired to `ConflictManager`).
  - Replaced the `Activity` placeholder route in `MainActivity.kt` with live `ConnectivityManager.NetworkCallback` state + `ActivitySyncScreen`, updated `AndroidHubView.tsx` interactive preview, and added `OfflineStatusStage36Test.kt`.
- **Stage 1.4 Status:** Complete (2026-11-14).
  - Added server-side Supabase Edge Function `supabase/functions/recognize-product/index.ts` (`@google/genai`, `gemini-4-hydrogen`, `ThinkingLevel.LOW`, structured JSON schema) with `GEMINI_API_KEY` kept strictly server-side and catalogue-ID validation on both server and client.
  - Added `CloudRecognitionModels.kt` (`CloudCostControlPolicy` enforcing local-first skip on `STRONG_MATCH`, compact OCR-prioritized `catalog_context`, 40/day soft budget, and `CloudRecognitionValidator` rejecting unknown/archived IDs), `RecognitionTelemetryStore.kt` (24h image-hash deduplication cache + usage/failure counters), and `CloudProductRecognitionClient.kt` (512px JPEG quality-72 compression leaving `originalPath` untouched).
  - Integrated cloud fallback into `OnDeviceProductRecognizer.kt`, `RecognitionRanker.kt`, `RecognitionResultScreen.kt` (plain-language cloud status + explicit `"Try Cloud Match"` retry + preserved photo on failure), and `MainActivity.kt` (auto-fallback only on `UNIDENTIFIED` when online, plus `recordConfirmedSelection` alias learning & audit logging).
  - Fixed `SupabaseProductRepository.syncCatalogueAndRebuildIndex(forceNetwork: Boolean = true)` parameter signature for `MainActivity.kt` and `EmofahSyncWorker.kt`, updated `build.gradle.kts` / `SupabaseConfig.kt` with optional `CLOUD_RECOGNITION_URL`, and added `CloudRecognitionStage14Test.kt`.
- **Stage 1.5 Status:** Complete (2026-11-14).
  - Created additive SQL migration `supabase/migrations/20261114_register_android_product_rpc.sql` (`public.android_product_idempotency` + idempotent `public.register_android_product(...)` RPC with normalized-name duplicate protection).
  - Implemented `ProductRegistrationModels.kt` (`ProductDraftSuggestionExtractor` for editable OCR/cloud hints, `ProductDraftValidator` requiring `sell_price > 0` and preventing duplicate registration against active items & pending drafts), `SupabaseProductRegistrationClient.kt` (RPC client + best-effort compact JPEG upload to `product-images/android-refs/`), and `ProductRegistrationRepository.kt` (online commit + offline `PendingProductDraftEntity` with negative temp `ProductEntity` ID, `SyncQueueEventEntity(PRODUCT_DRAFT)`, `dHash` reference copy, and immediate `RecognitionReferenceIndex` update).
  - Extended `EmofahDaos.kt`, `SyncQueueModels.kt`, `SyncQueueProcessor.kt`, and `SaleTransactionRepository.kt` for dependency-ordered `PRODUCT_DRAFT` sync and automatic child `SALE` payload/line-item relinking from temporary local ID to canonical server `products.id`.
  - Built `UnknownProductRegistrationScreen.kt` and wired `"Not in List"` → `UnknownProductRegistrationScreen` → `RecordSaleScreen` in `MainActivity.kt`, plus `ProductRegistrationStage15Test.kt`.
- **Stage 1.6 Status:** Complete (2026-11-14).
  - Created additive SQL migration `supabase/migrations/20261114_record_recognition_correction_rpc.sql` (`public.android_recognition_corrections` + idempotent `public.record_android_recognition_correction(...)` RPC).
  - Implemented `RecognitionLearningModels.kt` (`AliasExtractionPolicy` filtering noise/prices, `RecognitionLearningPolicy` recording suggested vs actual selection, enforcing `isHumanConfirmed == true` so unconfirmed guesses never learn, and honoring `allowImageAssociation`), `SupabaseRecognitionCorrectionClient.kt`, and `RecognitionLearningRepository.kt`.
  - Updated `RecognitionRanker.kt` (`captureDHashHex`), `OnDeviceProductRecognizer.kt` (hydrating Room photo visual signatures + delegating `recordConfirmedSelection`), `RecognitionResultScreen.kt` (explicit opt-in packaging photo/clue checkbox + `RecognitionSelectionOrigin`), `SyncQueueModels.kt`, `SyncQueueProcessor.kt` (live `RECOGNITION_CORRECTION` sync + dependency relinking after `PRODUCT_DRAFT` sync), and `MainActivity.kt`.
  - Added `RecognitionLearningStage16Test.kt`.
- **Stage 2.3 Status:** Complete (2026-11-14).
  - Created additive SQL migration `supabase/migrations/20261114_record_android_sale_price_override.sql` adding `unit_sell_price`, `original_sell_price`, `price_override_reason`, and `actor_role` audit columns + server-side role/reason verification in `public.record_android_sale` while preserving backwards compatibility for 9-arg default-price callers.
  - Added `SalePreferencesStore.kt` (persists last selected payment method and optional `autoReturnToScan` preference), added `StaffRole` (`CASHIER` default, `SUPERVISOR`, `OWNER_MANAGER`) to `DeviceIdentityProvider.kt`, and added `PriceOverridePolicy` + override tracking (`originalSellPrice`, `priceOverrideReason`) in `SaleCalculator.kt`.
  - Updated `SaleTransactionRepository.kt`, `SupabaseSaleRpcClient.kt`, and `SyncQueueModels.kt` to block unauthorized price overrides before DB/network commit and log `SALE_RECORDED_PRICE_OVERRIDE` audit events with `beforeJson`/`afterJson`.
  - Updated `RecordSaleScreen.kt` & `MainActivity.kt` with top-pinned post-sale `"Scan Next Product"` CTA, auto-return toggle, persisted payment selection, and role-gated price exception editor, plus `SaleSpeedAndOverrideStage23Test.kt`.
- **Stage 2.4 Status:** Complete (2026-11-14).
  - Created additive SQL migration `supabase/migrations/20261114_reverse_android_sale_rpc.sql` (`public.android_sale_reversals` + idempotent `SECURITY DEFINER` `public.reverse_android_sale(...)` RPC) enforcing Supervisor/Owner-Manager role authorization, non-blank reversal reason, double-reversal idempotency, non-destructive preservation of `public.sales`, atomic `public.products.qty` restoration, and `SALE_REVERSAL_CREDIT_ADJUSTMENT` accounting treatment.
  - Created `SaleHistoryModels.kt` (`SaleHistoryMapper`, `SaleHistorySyncBadge`, `SaleReversalPolicy`, `ReverseSaleOutcome`), `SupabaseSaleReversalClient.kt`, and `SaleHistoryRepository.kt` (handles unsynced local cancellation, online atomic RPC reversal, and offline queued `SALE_REVERSAL` with provisional stock restoration, `PendingFinancialEventEntity` adjustment, and `LocalAuditEventEntity`).
  - Extended `EmofahDaos.kt`, `SyncQueueModels.kt` (`SyncEventType.SALE_REVERSAL`), and `SyncQueueProcessor.kt` for background & manual reconciliation of queued reversals.
  - Upgraded `ActivitySyncScreen.kt` & `MainActivity.kt` with Today's Recorded Sales & Earlier Sales list (product name, quantity, total, time, payment method, price override indicator, sync badge), tap-to-open detail inspection modal, role-guarded safe reversal flow, and `SaleHistoryCorrectionStage24Test.kt`.
- **Stage 4.1 Status:** Complete (2026-11-14).
  - Created `StockLookupModels.kt` (`LowStockLevel`, `StockMovementEntry`, `StockLookupSnapshot`, and `StockLookupPolicy` evaluating low-stock thresholds, freshness timestamps + pending offline delta, honest metadata location extraction without fabrication, and local/remote movement deduplication).
  - Created `StockLookupRepository.kt` refreshing authoritative `public.products` stock & `public.sales` movements (and optional `public.settings` threshold) when online while preserving Room `pending_delta_qty` and serving full cached lookup offline.
  - Added `ProductDao.observeAllStockProjections()`, `ProductSaleMovementRow`, and `SaleDao.getRecentMovementsForProduct()` in `EmofahDaos.kt` (Room `SCHEMA_VERSION` unchanged at `1`).
  - Built native Jetpack Compose `StockLookupScreen.kt` and wired `ScanPurpose` routing (`Scan Product for Stock` -> `CameraCaptureScreen` -> `RecognitionResultScreen` -> `StockLookupScreen`, plus `"Sell This Product"` shortcut) in `MainActivity.kt`, plus `StockLookupStage41Test.kt`.
- **Stage 4.2 Status:** Complete (2026-11-14).
  - Created additive SQL migration `supabase/migrations/20261114_receive_android_stock_rpc.sql` (`public.android_stock_receipts` table + idempotent `SECURITY DEFINER` `public.receive_android_stock(...)` RPC) enforcing valid positive quantity, atomic `public.products.qty` increment, optional `public.products.buy_price` update, double-tap idempotency protection via unique `client_receipt_id`/`idempotency_key`, and ledger record insertion into `public.stock_movements`.
  - Implemented `StockReceivingModels.kt` (`StockReceiptDraft`, `StockReceivingValidation`, `StockReceivingValidator` enforcing `quantityReceived >= 1` and non-negative unit cost, `AndroidStockReceiptRpcRequest`, `StockReceiptRpcOutcome`, `StockReceivingResult`), `SupabaseStockReceivingClient.kt`, and `StockReceivingGateway`.
  - Added Room DAO methods in `EmofahDaos.kt` (`updateProductBuyPrice`, `findStockMovementEventByReceiptId`, and `@Transaction reconcileSyncedStockReceiptAtomically`), `SyncQueueModels.kt` (`SyncEventType.STOCK_MOVEMENT`, `parseStockMovementRpcRequest`, `relinkStockMovementPayloadProductId`), and updated `SyncQueueProcessor.kt` for background/online stock movement syncing with child draft relinking.
  - Built `StockReceivingRepository.kt` coordinating online atomic RPC execution with offline fallback (updates `local_stock_projections.pending_delta_qty += quantityReceived`, updates `products.buyPrice` if enabled, records `LocalAuditEventEntity`, records optional `PendingFinancialEventEntity` (`OUTFLOW`, `SUPPLIER_PAYMENT`), and enqueues `SyncQueueEventEntity(STOCK_MOVEMENT)`).
  - Built `ReceiveStockScreen.kt` with product selector, quantity stepper & quick +5/+10/+20/+50 chips, supplier & unit cost input with buy-price update toggle, invoice reference input, and primary `"RECEIVE X UNITS"` button.
  - Connected `MainActivity.kt` (`ScanPurpose.RECEIVE_STOCK`, `HomeScreen` "Receive Stock" quick button, `StockLookupScreen` "Receive" CTA, and `ReceiveStockScreen` sub-mode) and added `StockReceivingStage42Test.kt`.
- **Stage 4.3 Status:** Complete (2026-11-14).
  - Created additive SQL migration `supabase/migrations/20261114_adjust_android_stock_rpc.sql` (`public.android_stock_adjustments` table + idempotent `SECURITY DEFINER` `public.adjust_android_stock(...)` RPC) enforcing structured reason codes (`DAMAGED`, `EXPIRED`, `THEFT_OR_SHRINKAGE`, `INTERNAL_USE`, `COUNT_CORRECTION_DECREASE`, `COUNT_CORRECTION_INCREASE`), mandatory explanation notes (min 3 chars), non-negative stock clamping (`qty >= 0`), double-tap idempotency protection via unique `client_adjustment_id`/`idempotency_key`, and audit logging.
  - Implemented `StockAdjustmentModels.kt`, `SupabaseStockAdjustmentClient.kt`, and `StockAdjustmentScreen.kt`, plus `StockAdjustmentStage43Test.kt`.
- **Stage 4.4 Status:** Complete (2026-11-14).
  - Created additive SQL migration `supabase/migrations/20261114_physical_stock_count_rpc.sql` (`public.android_stock_count_sessions` & `public.android_stock_count_items` tables + idempotent `SECURITY DEFINER` `public.submit_physical_stock_count(...)` RPC).
  - Implemented `StockCountModels.kt`, `StockCountGateway.kt`, `StockCountRepository.kt`, and `PhysicalStockCountScreen.kt`, plus `PhysicalStockCountStage44Test.kt`.
- **Stage 4.5 Status:** Complete (2026-11-16).
  - Added direct **New Product** registration entry points inside `StockLookupScreen.kt` (action bar + empty-search shortcut) and `ReceiveStockScreen.kt` (missing product fallback passing typed search hints).
  - Enhanced `UnknownProductRegistrationScreen.kt` with optional packaging photo capture (`ScanPurpose.STOCK_REGISTER_PHOTO`), custom header/back labels, and Stock-flow completion CTAs (**Open in Stock Lookup** or **Receive Additional Units**).
  - Reused validated `ProductRegistrationRepository` (`register_android_product` RPC + offline temp product/queue fallback) in `MainActivity.kt` and added `StockProductRegistrationStage45Test.kt`.
- **Stage 4.6 Status:** Complete (2026-11-16).
  - Created `StockAlertModels.kt` (`StockAlertPolicy.buildSummary`, `StockAlertItem`, `StockAlertSummary`) evaluating Out-of-Stock (`<= 0`) and Low-Stock (`<= threshold`) alerts from active catalogue items + Room `LocalStockProjectionEntity.provisionalAvailableQty`.
  - Exposed `StockLookupRepository.fetchConfiguredThresholdOrNull()` reading `low_stock_threshold`/`stock_alert_threshold` from Supabase `public.settings`.
  - Added expandable actionable Stock Alerts banner with one-tap `"Restock"` shortcuts + `"All"` / `"Alerts"` / `"Out"` filter chips in `StockLookupScreen.kt`, real `"Stock alerts"` counter row in `HomeScreen.kt`, wiring in `MainActivity.kt`, and `StockAlertsStage46Test.kt`.
- **Stage 6.1 Status:** Complete (2026-11-16).
  - Inspected live `public.orders` schema directly against Supabase (`id`, `created_at`, `status`, `total_price`, `items`; confirmed `order_items` does not exist).
  - Implemented `OrderModels.kt` (`StoreOrderStatus`, `StoreOrderItem`, `StoreOrderRecord`, `StoreOrderParser` resiliently decoding array/object/string `items` & enriching names from cached `ProductIdentity`), `SupabaseOrderRepository.kt` (live REST fetch + `SharedPreferences` offline read cache), Jetpack Compose `OrdersScreen.kt` (filters, search, detail dialog, honest empty state) replacing the `Orders` placeholder in `MainActivity.kt`, and `OnlineOrderRetrievalStage61Test.kt`.
- **Stage 6.2 Status:** Complete (2026-11-16).
  - Created additive idempotent SQL migration `supabase/migrations/20261116_update_android_order_status_rpc.sql` (`public.android_order_status_events` audit table + `SECURITY DEFINER` `public.update_android_order_status(...)` RPC) enforcing UUID validation, row-level `FOR UPDATE` locking, allowed lifecycle transitions (`Pending -> Confirmed -> Ready -> Fulfilled` or `Cancelled` with mandatory reason), terminal state protection, and idempotency without altering sale/stock conversion (reserved for Stage 6.3).
  - Extended `OrderModels.kt` (`OrderStatusTransitionPolicy`, `PendingOrderStatusOverlay`, `StoreOrderParser.updateOrderStatusInRawJson`), created `SupabaseOrderStatusClient.kt` (`HttpURLConnection` RPC gateway) and `OrderManagementRepository.kt` (online atomic RPC execution + offline `SyncEventType.ORDER_UPDATE` queueing & local pending overlay), updated `EmofahDaos.kt`, `SyncQueueModels.kt`, `SyncQueueProcessor.kt`, `EmofahSyncWorker.kt`, `OrdersScreen.kt` (Pending Sync chip, action buttons, cancellation reason confirmation), `MainActivity.kt`, and added `OrderManagementStage62Test.kt`.
- **Stage 6.3 Status:** Complete (2026-11-16).
  - Created additive idempotent SQL migration `supabase/migrations/20261116_convert_android_order_to_sale_rpc.sql` (`public.android_order_sale_conversions`, `public.android_order_sale_conversion_items`, and `SECURITY DEFINER` `public.convert_android_order_to_sale(...)` RPC) locking `public.orders` + `public.products` rows atomically, verifying sufficient stock before deducting `public.products.qty`, inserting real `public.sales` rows + `stock_movements` + raw `ORDER_SALE_CONVERSION` financial inflow (`PENDING_DESKTOP_RULES`), marking the order `fulfilled`, and blocking double conversion via unique `order_id` and `idempotency_key`.
  - Extended `OrderModels.kt` (`OrderConversionPolicy`, `ResolvedOrderSaleLine`, RPC/result contracts), created `SupabaseOrderConversionClient.kt` and `OrderConversionRepository.kt`, updated `SupabaseOrderRepository.kt` (`converted_order_ids_json`), `EmofahDaos.kt`, `SyncQueueModels.kt` (`SyncEventType.ORDER_TO_SALE`), `SyncQueueProcessor.kt`, `EmofahSyncWorker.kt`, `OrdersScreen.kt` (`"Sale Recorded"` chip, `"Convert to Sale & Deduct Stock"` flow with payment selector & explicit SKU picker for unlinked lines without guessing IDs), `MainActivity.kt`, and added `OrderToSaleConversionStage63Test.kt`.
- **Stage 5.1 Status:** Complete (2026-11-14).
  - Configured Supabase with default out-of-the-box fallback credentials in `build.gradle.kts` so environment variables are not required to be manually touched.
  - Created Supabase SQL migration `supabase/migrations/20261114_financial_events_table.sql` (`public.android_financial_events` and `public.android_financial_idempotency`).
  - Implemented `FinancialEventModels.kt`, `FinancialEventValidator.kt`, and unit tests `FinancialEventFoundationStage51Test.kt` utilizing Room's `PendingFinancialEventEntity`.
- **Stage 5.2 Status:** Complete (2026-11-15).
  - Declared `RECEIVE_SMS` & `READ_SMS` permissions, optional `android.hardware.telephony`, and `MpesaSmsReceiver` in `AndroidManifest.xml`.
  - Implemented `FinancialMonitoringPrefs.kt` (explicit opt-in consent & revocable capture state), `MpesaMessageDetector.kt` (`MpesaDetectionPolicy` restricting live capture to official M-Pesa senders and filtering OTPs/promos/personal SMS), and `MpesaMonitoringRepository.kt` (deduplication by `transaction_reference`, Room `PendingFinancialEventEntity` persistence, audit trail, plus manual cash/expense entry).
  - Built native Jetpack Compose `MoneyScreen.kt` replacing the `EmofahDestination.Money` placeholder in `MainActivity.kt`, updated `AndroidHubView.tsx` interactive Money preview, and added `MpesaMessageMonitoringStage52Test.kt`.
  - Fixed Vite 8 dev server host-header blocking by adding `allowedHosts: true` in `vite.config.ts`.
- **Stage 5.3 Status:** Complete (2026-11-15).
  - Created `MpesaMessageParser.kt` (`MpesaTransactionChannel`, `MpesaParseStatus`, `ParsedMpesaMessage`, `MpesaMessageParser.parseAndValidate`) supporting Received, Paybill, Buy Goods/Till, Send Money, Agent Withdrawal, Deposit, Airtime, Fuliza, and Reversal SMS formats with transaction fee, new balance, account/phone extraction, and ambiguous-vs-malformed validation guards.
  - Integrated parser into `MpesaMonitoringRepository.kt` and upgraded `MoneyScreen.kt` + `AndroidHubView.tsx` to display parsed channels, fees, balances, and review flags, plus `MpesaParserValidationStage53Test.kt`.
  - Created `USER_SETUP_CHECKLIST.md` consolidating all user-side Supabase SQL migrations, Edge Function deployment, Android dependencies, and APK build/install steps.
- **Stage 4.4 Fix + Stage 5.5 Financial Synchronisation Status:** Complete (2026-11-16).
  - Fixed Stage 4.4 `StockCount*` compile and offline-sync mismatches: rewrote `StockCountGateway.kt` & `SupabaseStockCountClient.kt` to use `com.emofah.agencies.data.SupabaseConfig` and `java.net.HttpURLConnection` (removing undeclared `okhttp3`), updated `StockCountRepository.kt` and `PhysicalStockCountScreen.kt` to use real `EmofahDaos.kt` methods (`getActiveProducts()`, `upsertEvent()`, `appendAudit()`, `LocalContext.current.applicationContext`, discrepancy reason chips), added `SyncEventType.STOCK_COUNT` handling in `SyncQueueModels.kt` & `SyncQueueProcessor.kt`, and hardened `20261114_physical_stock_count_rpc.sql`.
  - Added `supabase/migrations/20261116_record_android_financial_event_rpc.sql` (`record_android_financial_event` RPC with dual `idempotency_key` + `reference_code` duplicate prevention and `PENDING_DESKTOP_RULES` default).
  - Implemented `SupabaseFinancialEventClient.kt`, `SyncQueuePolicy.parseFinancialEventRpcRequest`, `SyncQueueProcessor.processFinancialEvent`, `MpesaMonitoringRepository` atomic `FINANCIAL_EVENT` queue insertion, `MpesaSmsReceiver` immediate `SyncScheduler` trigger, and aligned `MoneyScreen.kt` so Android never decides final business accounting classifications.
  - Added `FinancialEventSyncStage55Test.kt` and expanded `PhysicalStockCountStage44Test.kt`.
- **Section 2 (Part 1) — Physical Purchase Receipt Scanning, OCR/AI Extraction & Human Review Status:** Complete (2026-11-16).
  - Added server-side Supabase Edge Function `supabase/functions/extract-receipt/index.ts` (`@google/genai`, `gemini-4-hydrogen`, `ThinkingLevel.LOW`, strict `catalog_context` ID guard) keeping `GEMINI_API_KEY` strictly server-side.
  - Added `SupabaseConfig.receiptExtractionEndpoint`, `PurchaseReceiptModels.kt` (`PurchaseItemCategory` for `RESALE_PRODUCT`/`CONSUMABLE`/`EQUIPMENT`, `PurchaseReceiptDraft` with `isHumanConfirmed = false` default, `ReceiptDraftValidator`, offline `ReceiptOcrParser`), and `SupabaseReceiptExtractionClient.kt` (ML Kit local OCR + online `extract-receipt` Edge Function + offline fallback).
  - Parameterized `CameraCaptureScreen.kt` framing hints/labels for receipts (`ScanPurpose.RECEIPT_CAPTURE`), built `ReceiptCaptureReviewScreen.kt` (photo preview, editable supplier/ref/date/total, line editor with category chips, catalogue SKU matcher, inline new resale product selling-price fields, and mandatory human confirmation checkbox), wired entry points in `HomeScreen.kt`, `MoneyScreen.kt`, and `MainActivity.kt`, and added `ReceiptExtractionStage61Test.kt`.
- **Section 2 (Part 2) — Confirmed Purchase Submission, New Resale Product Registration & Shared Inventory Update Status:** Complete (2026-11-16).
  - Created additive idempotent SQL migration `supabase/migrations/20261116_record_android_purchase_rpc.sql` (`public.android_purchases`, `public.android_purchase_items`, and `SECURITY DEFINER` `public.record_android_purchase` RPC) enforcing `is_human_confirmed = true`, registering new resale products with duplicate-name protection, incrementing shared `public.products` qty (+ `stock_movements` when present), keeping consumables/equipment separate from resale stock, optionally recording `PURCHASE_RECEIPT_OUTFLOW` in `android_financial_events` with `classification_status = 'PENDING_DESKTOP_RULES'`, and preventing duplicates.
  - Implemented `SupabasePurchaseClient.kt` and `PurchaseRepository.kt` (online atomic submission + offline temp product/provisional stock queueing + recognition index rebuild), added `SyncEventType.PURCHASE_RECEIPT` & Room transactions (`EmofahDaos.kt`, `SyncQueueModels.kt`, `SyncQueueProcessor.kt`), wired live `"Submit Confirmed Purchase"` & `"Scan Another Receipt"` into `ReceiptCaptureReviewScreen.kt` & `MainActivity.kt`, and added `PurchaseSubmissionStage62Test.kt`.
- **Section 4 — Service Operations (Printing, Photocopying, Scanning, Internet Browsing & E-Government Assistance) Status:** Complete (2026-11-16).
  - Created additive idempotent SQL migration `supabase/migrations/20261116_record_android_service_rpc.sql` (`public.android_service_operations`, `public.android_service_consumables`, and `SECURITY DEFINER` `public.record_android_service_operation` RPC) deduplicating via `idempotency_key`/`client_service_id`, deducting shared `public.products.qty` (+ `stock_movements` when present) for linked consumable SKUs without inventing product IDs, and recording raw `SERVICE_REVENUE` `INFLOW` events (`classification_status = 'PENDING_DESKTOP_RULES'`).
  - Implemented `ServiceOperationModels.kt`, `SupabaseServiceOperationClient.kt`, `ServiceOperationRepository.kt`, `SyncEventType.SERVICE_OPERATION` & atomic Room transactions (`EmofahDaos.kt`, `SyncQueueModels.kt`, `SyncQueueProcessor.kt`), Jetpack Compose `ServiceOperationsScreen.kt` wired to `HomeScreen.kt` & `MainActivity.kt`, and `ServiceOperationsStage63Test.kt`.
- **Stage 7.1 Status:** Complete (2026-11-16).
  - Implemented `DigitalReceiptModels.kt` (`DigitalReceiptData`, `ReceiptTransactionKind`, `DigitalReceiptFormatter` building deterministic receipts from committed product sales, online order conversions, and service operations while marking reversed sales `REVERSED / VOID` and never fabricating IDs/prices).
  - Implemented `DigitalReceiptExporter.kt` (`android.graphics.pdf.PdfDocument` 80mm-width PDF generation into `cacheDir/receipts/` + `FileProvider` `ACTION_SEND` PDF and plain-text sharing) and declared `FileProvider` in `AndroidManifest.xml` + `res/xml/emofah_file_paths.xml`.
  - Built reusable `DigitalReceiptPreviewDialog.kt` and wired receipt entry points into `RecordSaleScreen.kt`, `ActivitySyncScreen.kt`, `ServiceOperationsScreen.kt`, `OrdersScreen.kt`, `ServiceOperationRepository.kt`, and `MainActivity.kt`, plus `DigitalReceiptStage71Test.kt`.
- **Stage 7.2 Status:** Complete (2026-11-16).
  - Declared Bluetooth Classic SPP permissions (`BLUETOOTH`/`BLUETOOTH_ADMIN` `maxSdkVersion="30"`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` `neverForLocation`) and optional `android.hardware.bluetooth` feature in `AndroidManifest.xml`.
  - Implemented `BluetoothPrinterModels.kt` (`ThermalPaperWidth` `58mm`/`80mm`, `SavedThermalPrinter`, `BluetoothPrinterPrefs`), `EscPosReceiptFormatter.kt` (ESC/POS byte-command encoder with ASCII sanitization, column wrapping, `*** REVERSED / VOID ***` stamping, and alignment test slip), and `BluetoothPrinterManager.kt` (Android 12+ `BLUETOOTH_CONNECT` check + RFCOMM SPP `00001101-...` streaming).
  - Integrated printer status strip, runtime permission launcher, **Print Receipt**, **Select Bluetooth Printer** modal, paper-width chips, `"Pair in Settings"`, and `"Test Print"` into `DigitalReceiptPreviewDialog.kt`, plus `BluetoothPrintingStage72Test.kt`.
- **Stage 7.3 Status:** Complete (2026-11-16, completes Phase 7).
  - Declared `POST_NOTIFICATIONS` permission and `singleTop` launchMode in `AndroidManifest.xml`.
  - Implemented `NotificationModelsAndPrefs.kt` (`NotificationEvaluator` deduplication for unseen pending orders, conflict/auth sync signatures, and stock alert diffs, plus `NotificationPrefs`) and `EmofahNotificationManager.kt` (creating `emofah_orders_channel`, `emofah_sync_alerts_channel`, and `emofah_stock_alerts_channel`, respecting runtime permissions, and deep-linking taps to `Orders`, `Activity`, or `Stock`).
  - Wired channel registration in `EmofahApplication.kt`, background/foreground notification triggers in `EmofahSyncWorker.kt` & `MainActivity.kt`, and permission/toggle UI controls in `ActivitySyncScreen.kt`, plus `NotificationsStage73Test.kt`.
- **Stage 9.1 Status:** Complete (2026-11-16).
  - Implemented Supabase Auth (`GoTrue` `/auth/v1/token?grant_type=password`, `grant_type=refresh_token`, `/auth/v1/logout`) in `SupabaseAuthClient.kt`, domain session lifecycle & claim-to-role mapping (`AuthSessionModels.kt`), and AndroidKeyStore AES-GCM encrypted session storage (`AuthTokenStorage.kt`).
  - Built `AuthSessionRepository.kt` with single-flight `Mutex` token refresh, offline cached session continuity (`OFFLINE_CACHED`), safe session-expired pause (`SESSION_EXPIRED` without losing local Room records), `DeviceIdentityProvider` actor/role syncing, audit logging (`AUTH_SIGN_IN`, `AUTH_SESSION_EXPIRED`, `AUTH_SIGN_OUT`), and automatic sync queue resumption via `SyncScheduler.onAuthenticationRestored`.
  - Added global runtime bearer token resolution (`SupabaseConfig.registerAccessTokenProvider` & `SupabaseConfig.bearerToken`) across all 17 Supabase HTTP/RPC/Edge Function clients with automatic `anonKey` fallback when unauthenticated.
  - Integrated shared `authSessionRepository` in `EmofahApplication.kt`, proactive pre-sync refresh + 401/403 hold reporting in `EmofahSyncWorker.kt`, TopAppBar account icon + `StaffSignInDialog` + `StaffAuthStatusCard` in `AuthAccountDialog.kt`, `HomeScreen.kt`, `ActivitySyncScreen.kt`, and `MainActivity.kt`, plus `AuthSessionStage91Test.kt`.
- **Stage 9.2 Status:** Complete (2026-11-16).
  - Created additive idempotent SQL migration `supabase/migrations/20261116_rbac_and_rls_enforcement.sql`: establishes `public.android_staff_roles` (user-scoped read RLS, direct writes revoked from `anon`/`authenticated`), enables RLS & revokes direct REST access across all 18 `android_*` operational/audit/idempotency tables, adds `public.resolve_android_effective_role()` & `public.get_my_android_staff_profile()` (reading `android_staff_roles` then server-managed `app_metadata`, never user-writable `user_metadata`), and hardens `record_android_sale` (price overrides & disabled account checks), `reverse_android_sale`, `adjust_android_stock`, and `submit_physical_stock_count` (`UNAUTHORIZED_ROLE` enforcement while keeping Desktop & Storefront compatibility).
  - Implemented centralized Android RBAC policy in `RolePermissions.kt` (`EmofahPermission`, `PermissionCheckResult`, `ServerStaffProfile`, `ServerProfileReconciliation`, `RolePermissionPolicy`), exposed `grantedPermissions` in `AuthUiState` (`AuthSessionModels.kt`), removed `user_metadata` role fallback in `SupabaseAuthClient.kt` + added `fetchStaffProfile()`, and reconciled server staff roles & deactivation blocks on sign-in/refresh in `AuthSessionRepository.kt`.
  - Aligned `PriceOverridePolicy` (`SaleCalculator.kt`), `SaleReversalPolicy` (`SaleHistoryModels.kt`), `StockCountRepository.kt`, `PhysicalStockCountScreen.kt`, `StockAdjustmentScreen.kt`, `ActivitySyncScreen.kt` (guarding `"Reverse Locally"` conflict resolution), `AuthAccountDialog.kt` (displaying granted permissions), `SupabaseSaleRpcClient.kt` & `SupabaseStockAdjustmentClient.kt` (`UNAUTHORIZED_ROLE` rejection handling), and `MainActivity.kt` (reactive `authState.activeRole` propagation), plus `RolePermissionsStage92Test.kt`.
- **Stage 9.3 Status:** Complete (2026-11-16).
  - Created additive idempotent SQL migration `supabase/migrations/20261116_android_audit_events_rpc.sql`: establishes append-only `public.android_audit_events` protected by RLS and a `BEFORE UPDATE OR DELETE` immutability trigger (`prevent_android_audit_mutation`), plus idempotent batch ingestion RPC `public.record_android_audit_events(p_events jsonb)` (`ON CONFLICT (audit_id) DO NOTHING`, server-derived `auth_user_id`/`actor_role`) and role-scoped inspection RPC `public.get_android_audit_events(p_limit integer)`.
  - Added `EmofahPermission.VIEW_FULL_AUDIT_LOG` in `RolePermissions.kt` and non-schema-changing `AuditDao` sync helpers (`observePendingAuditCount`, `getPendingAudits`, `markAuditsServerConfirmed`, `observeRecentAudits(limit = 250)`) in `EmofahDaos.kt`.
  - Built `audit/AuditAccountabilityModels.kt` (`AuditCategory`, `AuditSyncFilter`, `AuditAccountabilityPolicy`), `audit/SupabaseAuditClient.kt`, and `audit/AuditSyncRepository.kt` (single-flight bounded batch flush preserving local logs offline and on transient/auth failures), wired into `EmofahSyncWorker.kt` and `MainActivity.kt` startup/manual sync.
  - Built Jetpack Compose `AuditTrailSection.kt` integrated as the 3rd `"Audit"` tab inside `ActivitySyncScreen.kt` (role-scoped view, metrics strip, search + category + sync-state filters, cloud trail loader, and expandable immutable before/after JSON snapshots), plus `AuditAccountabilityStage93Test.kt`.
- **Stage 9.4 Status:** Complete (2026-11-16, completes Phase 9).
  - Created `data/sync/ReliabilityRecoveryModels.kt` (`ReliabilityRecoveryPolicy`, `StoragePrunePlan`, `QueueIntegrityPlan`, `ReliabilityHealthSnapshot`, `RecoveryRunReport`) and `data/sync/ReliabilityRecoveryManager.kt`: performs one-time cold-process `IN_FLIGHT` crash recovery (`recoverAllInFlightOnColdStart`), self-heals child queue payloads still referencing negative temp product IDs after parent `PRODUCT_DRAFT` confirmation, surfaces dependency-deadlocked or malformed queue entries into `CONFLICT` instead of silent stalls, and safely prunes stale `captured_scans` & `receipts` caches while strictly protecting any photo path referenced by pending drafts, active queue items, or `ProductReferencePhotoEntity`.
  - Added non-schema-changing recovery queries to `EmofahDaos.kt` (`getAllReferencePhotos`, `recoverAllInFlightOnColdStart`, `getAllProductDraftsSnapshot`), integrated queue integrity self-healing directly inside `SyncQueueProcessor.processPendingQueue()`, periodic cache hygiene inside `EmofahSyncWorker.kt`, startup recovery + live diagnostics in `MainActivity.kt`, and a **Storage & Recovery Guard** card in `ActivitySyncScreen.kt` (Queue tab) with audit logging (`SYSTEM_RECOVERY_CLEANUP`), plus `ReliabilityRecoveryStage94Test.kt`.
- **Stage 10.1 Status:** Complete (2026-11-16).
  - Audited the end-to-end recognition pipeline (`CapturedImageProcessor.kt`, `ProductMetadataExtractor.kt`, `RecognitionReferenceIndex.kt`, `RecognitionRanker.kt`, `OnDeviceProductRecognizer.kt`, `CloudRecognitionModels.kt`, `CloudProductRecognitionClient.kt`, `RecognitionLearningModels.kt`, `ProductRegistrationModels.kt`) and resolved 4 real edge-case bugs:
    1. `RecognitionRanker.kt`: guarded `nameLower.isNotEmpty()` before `normalizedOcr.contains(nameLower)` (preventing empty/punctuation-only names from earning a `+0.35` boost), clamped `maxCandidates.coerceAtLeast(1)`, and tuned `variantAdjustment` from `-0.05` to `-0.02` so multi-word variants (e.g. `200 pgs` vs `100 pgs`) remain discoverable as `AMBIGUOUS_ALTERNATIVES` when variant text is occluded instead of dropping just below `MINIMUM_CANDIDATE_THRESHOLD`.
    2. `RecognitionReferenceIndex.kt`: guarded `searchByText` against non-positive `limit` and empty trimmed substring boosts.
    3. `CloudRecognitionModels.kt`: clamped `maxItems.coerceAtLeast(1)` inside `buildCompactCatalogueContext`.
    4. `RecognitionLearningModels.kt`: filtered price/quantity tag lines (`Price: 150`, `Total: 300`, `Qty: 2`, `Ksh 150 only`) in `AliasExtractionPolicy` so shelf price stickers are never learned as product aliases.
  - Added comprehensive Stage 10.1 regression test suite `ImageRecognitionStage101Test.kt` covering degenerate image dimensions/blur/lighting/EXIF flips, variant disambiguation, archived exclusion, cloud cost/budget/unknown-ID guardrails, human-confirmed-only alias & visual learning loops, and unknown-product registration duplicate/price validation.
  - **Repeatable On-Device Recognition Verification Matrix (Android APK):**
    1. *Good light & crisp label:* Scan registered variant (e.g. `Kasuku Exercise Book 200 pgs`) → confirm `STRONG_MATCH` locally with zero cloud usage and confirmation step before sale.
    2. *Partial/ambiguous packaging:* Scan sibling variant with occluded qualifier (e.g. `Cristal bic pen` or `Kasuku Exercise Book`) → confirm `AMBIGUOUS_ALTERNATIVES` and manual variant selection.
    3. *Dim/blurry capture:* Cover light or shake slightly → verify non-blocking quality warning + Retake option while still allowing offline OCR/Confirm.
    4. *Unfamiliar packaging (online & offline):* Verify `UNIDENTIFIED`, offline photo preservation (`OFFLINE_SKIPPED`), online cloud fallback respecting daily budget & rejecting fabricated IDs, or `Not in List` handoff with required selling price.
    5. *Confirmed learning loop:* Confirm/correct item with packaging clue opt-in → rescan same item and verify immediate `STRONG_MATCH` boost from updated local `RecognitionReferenceIndex` & `dHash`.
- **Stage 10.2 Status:** Complete (2026-11-16).
  - Audited the end-to-end Android sales pipeline (`SaleCalculator.kt`, `SalePreferencesStore.kt`, `DeviceIdentityProvider.kt`, `SaleTransactionRepository.kt`, `SupabaseSaleRpcClient.kt`, `SaleHistoryModels.kt`, `SaleHistoryRepository.kt`, `SupabaseSaleReversalClient.kt`, `RecordSaleScreen.kt`, `MainActivity.kt`) and resolved 2 real edge-case bugs:
    1. `SaleCalculator.kt` (`PriceOverridePolicy.validatePriceException`, `validateAndCalculate`, `parsePriceInput`, `createDraft`): blocked non-finite (`NaN`/`Infinity`) prices that previously bypassed IEEE 754 `<= 0.0` comparisons, and rejected negative/non-finite draft override prices inside `createDraft`.
    2. `SaleTransactionRepository.kt` (`recordSale`): added explicit entry-point domain bounds rejecting `draft.quantity <= 0` and non-finite or non-positive unit prices / totals prior to Room or Supabase RPC calls (including when both registered and requested prices are `0.0`).
  - Added comprehensive Stage 10.2 regression test suite `SalesTestingStage102Test.kt` covering discount price calculation, `NaN`/`Infinity` & integer-overflow parsing guards, stock-policy threshold checks (`STRICT_BLOCK` vs `ALLOW_WITH_WARNING`), offline temporary product dependency + payload relinking, RPC response states (`DUPLICATE_IGNORED`, `UNAUTHORIZED_ROLE`, `401/403 AuthRequired`, incomplete ACK), sync badge mapping, and non-destructive sale reversal guardrails.
  - **Repeatable On-Device Sales Verification Matrix (Android APK):**
    1. *One-touch standard sale (Online):* Scan/select product → verify default Qty `1` and remembered payment chip → tap `RECORD SALE` → confirm `SERVER_CONFIRMED`, atomic Supabase `sales` + `products.qty` decrement, and receipt preview.
    2. *Double-tap & idempotency safety:* Rapidly tap `RECORD SALE` twice or retry with same session seed → verify single sale recorded (`Duplicate Tap Prevented` / `already_committed`).
    3. *Rapid repeat scan speed:* Toggle `"Auto-open Scan camera right after recording a sale"` → record sale → verify direct return to `Scan` with no forced customer fields.
    4. *RBAC price exception:* Verify `CASHIER` sees locked price notice (`OVERRIDE_SALE_PRICE` blocked); sign in as `SUPERVISOR`/`OWNER_MANAGER`, override unit price + enter audit reason → verify `SALE_RECORDED_PRICE_OVERRIDE` audit log with before/after prices.
    5. *Sale history & non-destructive reversal:* Open `Activity` → inspect today's sale → reverse as `SUPERVISOR`/`OWNER_MANAGER` with reason → confirm original sale retained with `REVERSED` badge, stock restored, and `SALE_REVERSAL_CREDIT_ADJUSTMENT` event created.
- **Stage 10.3 Status:** Complete (2026-11-16).
  - Audited the offline-first storage, sync queue, conflict recovery, and background worker pipeline (`SyncQueueModels.kt`, `SyncQueueProcessor.kt`, `ConflictManager.kt`, `EmofahSyncWorker.kt`, `ReliabilityRecoveryModels.kt`, `ReliabilityRecoveryManager.kt`, `OfflineStatusModels.kt`, `EmofahDaos.kt`) and resolved 2 real edge-case bugs:
    1. `SyncQueueModels.kt` (`parseSaleRpcRequest`, `parseProductDraftPayload`, `parseSaleReversalRpcRequest`, `parseStockMovementRpcRequest`): added `isFinite()` validation on `totalPrice`, `sellPrice`, `buyPrice`, `total`, and `unitCost` (plus `initialQty >= 0`) so corrupted `NaN`/`Infinity` JSON payloads are rejected and quarantined cleanly instead of bypassing IEEE 754 `<= 0.0` checks.
    2. `ConflictManager.kt` (`retryAfterStockReplenished`): expanded `targetProductId` extraction from `SALE`-only to `SALE`, `STOCK_MOVEMENT`, `STOCK_ADJUSTMENT`, and `SALE_REVERSAL` so retrying any product-scoped conflicted queue item also clears its local stock-conflict flag atomically.
  - Added comprehensive Stage 10.3 offline regression suite `OfflineTestingStage103Test.kt` verifying parent-before-child dependency blocking (`PRODUCT_DRAFT` -> `SALE` / `RECOGNITION_CORRECTION`), cycle-safe topological sorting, orphaned negative-ID relinking across child stock/sale events, `NaN` & missing-parent quarantine, WorkManager `AUTH_REQUIRED` hold vs bounded retry limits, `OfflineStatusSummary` headlines, financial record immutability protection, and photo-safe cache pruning.
  - **Repeatable On-Device Offline & Reconnect Verification Matrix (Android APK):**
    1. *Airplane-mode catalogue & scan continuity:* Enable Airplane Mode → verify cached products, reference thumbnails, Stock Lookup, and offline OCR/dHash recognition continue operating without network errors.
    2. *Dependent offline operations:* While offline, register a new unknown product (`tempId < 0`), immediately sell it, and receive stock on it → confirm `Activity → Queue` shows pending count + `"Offline · queued safely on device"`.
    3. *Process kill & cold-start crash recovery:* Force-stop app while offline/pending → relaunch → verify all Room sales/drafts/photos remain intact and **Storage & Recovery Guard** recovers any interrupted `IN_FLIGHT` entries.
    4. *Reconnect & dependency-ordered sync:* Turn Wi-Fi/data back on → tap `"Sync Pending Items Now"` (or wait for WorkManager) → confirm parent `PRODUCT_DRAFT` commits first, child `SALE`/`STOCK_MOVEMENT` auto-relink to real server `products.id` and sync cleanly without duplicates.
    5. *Oversell conflict & safe review:* Sell remaining server stock from Desktop while Android is offline, then sell on Android and reconnect → verify Android marks the sale `CONFLICT` non-destructively in `Activity → Queue` with options to **Retry** after restock or **Reverse Locally** with audit log.
- **Stage 10.4 Status:** Complete (2026-11-16).
  - Audited the Phase 5 financial monitoring and M-Pesa pipeline (`FinancialEventModels.kt`, `FinancialMonitoringPrefs.kt`, `MpesaSmsReceiver.kt`, `MpesaMessageDetector.kt`, `MpesaMessageParser.kt`, `MpesaMonitoringRepository.kt`, `SupabaseFinancialEventClient.kt`, `EmofahDaos.kt`, `SyncQueueModels.kt`, `MoneyScreen.kt`, `AndroidManifest.xml`, and `20261116_record_android_financial_event_rpc.sql`) and resolved 5 real edge-case bugs:
    1. `FinancialEventModels.kt` (`FinancialEventDraft.isValid`, `FinancialEventValidator.validate`): added `.isFinite()` checks so `NaN` and `Infinity` amounts no longer bypass IEEE 754 `<= 0.0` / `> 0.0` validation checks.
    2. `MpesaMessageDetector.kt` & `MpesaMessageParser.kt`: tightened `TX_CODE_REGEX` to `\b([A-Z](?=[A-Z0-9]*\d)[A-Z0-9]{9})\b` (preventing 10-letter uppercase English words like `"STATIONERY"` or `"OPERATIONS"` from matching as M-Pesa transaction codes when a real code is missing) and added `.isFinite()` guards on parsed transaction amounts, fees, and balances.
    3. `MpesaMessageParser.kt`: expanded `AIRTIME_BUNDLE` channel matching for `"data"` / `"bundle"` purchases, added Paybill `"paid to ... for account"` and Deposit agent extraction, and supplied clean defaults (`"Safaricom Airtime"`, `"Safaricom Fuliza"`) so valid standard airtime/Fuliza alerts parse as `VALID_PARSED`.
    4. `EmofahDaos.kt` (`findByTransactionReference`) & `MpesaMonitoringRepository.kt` (`recordManualFinancialEvent`): switched local reference lookup to case-insensitive `UPPER(transaction_reference) = UPPER(:ref)` and normalized manual reference codes to uppercase, aligning local Room deduplication with Supabase `record_android_financial_event`.
    5. `SyncQueueModels.kt` (`parseFinancialEventRpcRequest`) & `MoneyScreen.kt`: added `.isFinite()` validation on queued financial amounts/fees/balances and comma-stripping + finite guard on manual entry form clearing.
  - Added Stage 10.4 regression suite `FinancialTestingStage104Test.kt` verifying NaN/Infinity rejection, consent & sender filtering, TX-code digit enforcement, multi-channel M-Pesa parsing, `PENDING_DESKTOP_RULES` accounting boundary separation, offline queue sanitization, RPC outcome/idempotency mapping, and financial immutability.
  - **Repeatable On-Device Financial Monitoring Verification Matrix (Android APK):**
    1. *Strict SMS consent & revocation gate:* Keep M-Pesa SMS monitoring paused → simulate incoming SMS → verify zero events are captured and `MpesaSmsReceiver` exits without reading message contents; enable consent + grant `RECEIVE_SMS`/`READ_SMS`, then toggle paused again and verify immediate capture halt.
    2. *Official sender & promo/OTP filtering:* Send/receive a personal SMS or Safaricom promo/OTP alongside a genuine `MPESA` confirmation → confirm personal/promo/OTP alerts increment `Filtered promos/non-M-Pesa` and never appear in the financial ledger.
    3. *Multi-channel M-Pesa paste/capture & duplicate lock:* Test Inflow, Paybill (with fee + account), Till, Withdrawal (`TRANSFER`), and Reversal in `Money` → verify fee, new balance, channel label, and `PENDING_DESKTOP_RULES` status; re-submit the exact same `transaction_reference` (or lowercase equivalent) and confirm `"Duplicate ignored"` locally and `"DUPLICATE_IGNORED"` on Supabase.
    4. *Manual cash/expense entry & offline sync:* Switch to `Manual Expense / Cash` in Airplane Mode, test `"0"`/`"NaN"` rejection and valid `"1,250"` entry → verify `LOCAL_PENDING` local persistence, reconnect Wi-Fi, tap `Sync`, and confirm `SERVER_CONFIRMED` in `android_financial_events` with `classification_status = 'PENDING_DESKTOP_RULES'`.
- **Stage 10.5 Status:** Complete (2026-11-16).
  - Executed full cross-platform contract audit across Android (`android/app/src/main/java/com/emofah/agencies/...`), 16 Supabase migrations (`supabase/migrations/*.sql`), Desktop web shell (`src/...`), and Edge Functions (`supabase/functions/...`):
    1. *Shared Catalog (`public.products`):* Verified canonical integer PK, variant representation as distinct product rows (preserving individual IDs, prices, and quantities), brand hint extraction (`Kasuku`, `Bic`, `Express`, `Deli`), and additive product registration deduplication (`COMMITTED`, `DUPLICATE_IGNORED`, `DUPLICATE_EXISTING`).
    2. *Shared Sales Ledger (`public.sales`):* Verified atomic `record_android_sale` RPC payload mapping, price override validation (`actor_role` checking against `public.android_staff_roles`), and non-destructive `reverse_android_sale` (preserving original `public.sales` row, restoring stock, setting `ALREADY_REVERSED` on replay, and rejecting cashier attempts with `UNAUTHORIZED_ROLE`).
    3. *Shared Storefront Order Lifecycle (`public.orders`):* Verified operational transition matrix in `OrderStatusTransitionPolicy` and `update_android_order_status` RPC (`pending` -> `confirmed` -> `ready` -> `fulfilled` / `cancelled`), cancellation reason enforcement (min 3 chars), and terminal state immutability.
    4. *Order-to-Sale Conversion (`convert_android_order_to_sale`):* Verified atomic row-lock multi-line conversion, sufficient stock validation before deduction, creation of real `public.sales` rows, one-time conversion guard (`order_id UNIQUE`), `ALREADY_CONVERTED` vs `DUPLICATE_IGNORED` handling, and raw financial inflow creation (`PENDING_DESKTOP_RULES`).
    5. *Shared Stock Operations:* Verified receiving (`receive_android_stock`), adjustments with reason codes and non-negative clamping (`adjust_android_stock`), and physical stock count sessions with variance reconciliation (`submit_physical_stock_count`).
    6. *Confirmed Purchases & Service Operations:* Verified `record_android_purchase` (new resale product registration vs consumable/equipment separation) and `record_android_service_operation` (printing/photocopying revenue + linked consumable SKU stock deduction).
    7. *Financial Ledger & Desktop Accounting Boundary:* Verified that Android exclusively records raw operational evidence tagged with `classification_status = 'PENDING_DESKTOP_RULES'`, leaving final accounting classification authority strictly with Desktop.
    8. *Audit Trail & Immutability:* Verified `record_android_audit_events` batch serialization, database trigger immutability (`prevent_android_audit_mutation`), and role-scoped `get_android_audit_events` cloud inspection.
  - Added comprehensive Stage 10.5 regression test suite `CrossPlatformStage105Test.kt` in `android/app/src/test/java/com/emofah/agencies/`.
  - **Repeatable Cross-Platform Verification Matrix (Android ↔ Supabase ↔ Desktop ↔ Storefront):**
    1. *Shared catalogue sync & variant integrity:* Open Desktop catalog (`src/components/ProductCatalogView.tsx`) and Android (`StockLookupScreen.kt`) → confirm identical 111 items, identical variants (e.g. `Kasuku 200 pgs` vs `100 pgs`), and consistent pricing without ID divergence.
    2. *Storefront order lifecycle & terminal lock:* Place order in Storefront (`public.orders` status `pending`) → open Android `Orders` → tap **Confirm Order** (`confirmed`) → tap **Mark Ready** (`ready`) → verify both Android and Storefront see status change; try invalid backwards transition or double terminal mutation and confirm `INVALID_TRANSITION` rejection.
    3. *Order-to-sale fulfillment & stock consistency:* Select ready order in Android `Orders` → tap **Convert to Sale & Deduct Stock** → confirm stock decrements in `public.products`, sale row inserts into `public.sales`, order marks `fulfilled`, and re-conversion attempt from another client returns `ALREADY_CONVERTED`.
    4. *Desktop accounting authority separation:* Trigger a sale, M-Pesa event, or service operation on Android → inspect `public.android_financial_events` → verify `classification_status = 'PENDING_DESKTOP_RULES'`; confirm Android never fabricates final general ledger accounting categories.
    5. *Role-guarded reversals & audit integrity:* Perform sale reversal as `SUPERVISOR` → confirm original sale preserved in `public.sales`, stock restored in `public.products`, reversal recorded in `android_sale_reversals`, and immutable audit trail visible in `Activity → Audit`.
- **Stage 10.6 Status:** Complete (2026-11-17, Final Delivery & Handover).
  - Consolidated and verified the complete production deliverables for Emofah Agencies across all 10 Blueprint phases:
    1. *Native Android Application (`com.emofah.agencies`):* 100% Kotlin + Jetpack Compose + Room `2.6.1` + WorkManager `2.9.1` + CameraX `1.4.0` + Google ML Kit `16.0.1`. Real 7-destination navigation (`Home`, `Scan`, `Record Sale`, `Stock`, `Money`, `Orders`, `Activity`), offline-first sync engine with single-flight mutex and topological dependency sorting, encrypted Keystore Supabase Auth session lifecycle, role-based access control, digital PDF receipts, and ESC/POS Bluetooth thermal printing.
    2. *Additive Database Migrations (16/16 Complete):* All 16 PostgreSQL migrations consolidated in `supabase/migrations/` and documented in `USER_SETUP_CHECKLIST.md`. Non-destructive, row-locked, idempotent `SECURITY DEFINER` RPCs protecting shared `public.products`, `public.sales`, `public.orders`, `public.android_financial_events`, and immutable `public.android_audit_events`.
    3. *Server-Side Supabase Edge Functions:* `recognize-product` and `extract-receipt` deployed using modern `@google/genai` with `gemini-4-hydrogen`, strict schema validation, catalogue-ID boundary guards, and server-side secret management (`GEMINI_API_KEY`).
    4. *Test Verification Suite (41 Test Suites):* Complete unit and regression test coverage across all domains (`ImageRecognitionStage101Test`, `SalesTestingStage102Test`, `OfflineTestingStage103Test`, `FinancialTestingStage104Test`, `CrossPlatformStage105Test`, `RolePermissionsStage92Test`, `AuditAccountabilityStage93Test`, `ReliabilityRecoveryStage94Test`, etc.).
    5. *Build & Distribution Pipeline:* Automated GitHub Actions CI workflow `.github/workflows/android-apk.yml` (JDK 17 + Android SDK 34 + Gradle 8.9 building `app-debug.apk` and running unit tests) alongside local Android Studio CLI build instructions.
    6. *Comprehensive Documentation:* Complete `README.md` at project root, `USER_SETUP_CHECKLIST.md` step-by-step migration guide, and verified `PROJECT_GUIDE.md` tracking all 36 stages from Phase 0 through Phase 10.
- **Android Application Package / Entry Point:** `com.emofah.agencies` — `android/app/src/main/java/com/emofah/agencies/EmofahApplication.kt` & `MainActivity.kt`.
- **APK Distribution / Build Mechanism:** GitHub Actions workflow `.github/workflows/android-apk.yml` (produces `app-debug.apk` artifact) or local Android Studio / `gradle assembleDebug` + `adb install`.

---

## 6. Android Build, Dependencies & APK Install Checklist (Updated Every Stage)

### Required Tools (Local Machine or GitHub Actions CI)
- **JDK:** 17 (Temurin)
- **Android SDK:** Compile SDK `34`, Target SDK `34`, Min SDK `26` (Android 8.0 Oreo+)
- **Gradle:** `8.9` (AGP `8.7.2`, Kotlin `2.0.21`, KSP `2.0.21-1.0.27`)

### Android Libraries Configured in `android/app/build.gradle.kts`
- Jetpack Compose BOM `2024.10.01` (`material3`, `material-icons-extended`, `activity-compose:1.9.3`)
- Navigation Compose `2.8.3` & Lifecycle Runtime Compose `2.8.7`
- Room `2.6.1` (`room-runtime`, `room-ktx`, `room-compiler` via KSP, `room-testing`)
- WorkManager `2.9.1` (`androidx.work:work-runtime-ktx`)
- CameraX `1.4.0` (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) & `exifinterface:1.3.7`
- Google ML Kit Text Recognition `16.0.1` (`com.google.mlkit:text-recognition`)

### Configuration & Database Migration Before Building/Running APK
1. Follow **`USER_SETUP_CHECKLIST.md`** at the repository root — run the unified master script **`supabase/COMPLETE_EMOFAH_BACKEND_SETUP.sql`** (or individual migrations starting with `20261113_foundational_schema.sql`), deploy Edge Functions (`recognize-product` and `extract-receipt`), and verify `product-images` storage.
2. Build & test (Supabase URL & publishable anon key defaults are pre-configured in `android/app/build.gradle.kts`, so no manual env var edits are needed):
   - `cd android && gradle testDebugUnitTest assembleDebug`
   - Output APK: `android/app/build/outputs/apk/debug/app-debug.apk`
   - Install on phone: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (or use GitHub Actions `.github/workflows/android-apk.yml`).
