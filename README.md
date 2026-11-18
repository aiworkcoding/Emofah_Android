# Emofah Agencies — Native Android Application & Cloud Architecture

> **"Equipping Work. Enabling Progress."**  
> *Production-grade native Android Point-of-Sale, Image-First Product Scanning, Offline-First Synchronization Engine, Financial Activity Monitoring, Order Fulfillment, and Receipt Printing system for Emofah Agencies.*

---

## 1. System Overview

**Emofah Agencies** is a full-featured, native Android enterprise application (`com.emofah.agencies`) engineered for high-velocity stationery, cybercafe, and retail agency operations in Kenya. It connects directly to a shared Supabase PostgreSQL backend, synchronizing inventory, sales, customer orders, and financial evidence in real-time across Android mobile terminals, Desktop management dashboards, and web storefronts.

### Core Architectural Pillars
1. **Real Native Android Application:** 100% Kotlin + Jetpack Compose + Material 3, CameraX `1.4.0`, Google ML Kit OCR `16.0.1`, Room `2.6.1`, and WorkManager `2.9.1`.
2. **Offline-First Resilience:** Room SQLite transactional local cache with a dependency-ordered sync queue, single-flight mutex processor, exponential backoff, topological sort (parents before children), and non-destructive stock conflict reconciliation.
3. **Image-First Product Recognition:** Local-first on-device perceptual hash (`dHash`) + ML Kit text recognition with server-side Gemini 2.5 Flash / Hydrogen cloud fallback, human confirmation gates, and adaptive alias learning.
4. **Frictionless Sales Capture:** One-touch transactional recording, double-tap prevention, backend idempotency keys, role-governed price exceptions, and non-destructive sales reversals.
5. **Financial & M-Pesa Monitoring:** Automated local SMS capture for official `MPESA` alerts, multi-channel parsing (Inflows, Paybill, Till, Withdrawals, Airtime, Reversals), and strict accounting boundary separation (`PENDING_DESKTOP_RULES`).
6. **Storefront Order Fulfillment:** Atomic conversion of online orders into sales ledger records with row-lock stock verification, preventing double-selling.
7. **Hardware & Digital Receipts:** Vector 80mm PDF receipt generation with Android Share integration, alongside ESC/POS Bluetooth thermal printing (58mm/80mm).
8. **Enterprise RBAC & Immutability:** Supabase GoTrue Auth with Keystore-encrypted AES-GCM token storage, server-side role validation (`CASHIER`, `SUPERVISOR`, `OWNER_MANAGER`), Row Level Security across all 18 operational tables, and append-only audit trail protected by database triggers.

---

## 2. Shared Source of Truth & Database Architecture

The application operates against a single shared Supabase PostgreSQL database (`https://nbmmfdiqywmkjaltezro.supabase.co`). All mutations are executed via idempotent, row-locked `SECURITY DEFINER` stored procedures.

### Primary Database Entities
- **`public.products`:** Shared master catalogue (integer `id`, `name`, `qty`, `sell_price`, `buy_price`, `image_url`, `category`). Variants exist as distinct product rows with unique IDs.
- **`public.sales`:** Authoritative sales ledger (`id`, `product_id`, `qty_sold`, `total_price`, `sale_date`). Android inserts real rows and decrements stock atomically.
- **`public.orders`:** Shared storefront order pool (`id` UUID, `status`, `total_price`, `items` JSONB). Valid transitions: `pending` → `confirmed` → `ready` → `fulfilled` / `cancelled`.
- **`public.android_financial_events`:** Operational financial evidence (M-Pesa SMS, manual cash/expenses, service revenue). Tagged with `classification_status = 'PENDING_DESKTOP_RULES'` so Desktop retains sole authority over business accounting charts.
- **`public.android_staff_roles`:** Authoritative staff accounts and assigned roles (`CASHIER`, `SUPERVISOR`, `OWNER_MANAGER`). Direct REST mutations revoked; enforced inside privileged RPCs.
- **`public.android_audit_events`:** Append-only immutable log protected by `prevent_android_audit_mutation` database trigger.

### All 16 Additive SQL Migrations
All migrations are located in `supabase/migrations/` and documented in `USER_SETUP_CHECKLIST.md`:
1. `20261114_record_android_sale_rpc.sql` — Atomic sale recording & idempotency.
2. `20261114_record_android_sale_price_override.sql` — Role-gated price exceptions.
3. `20261114_reverse_android_sale_rpc.sql` — Non-destructive supervisor sale reversals.
4. `20261114_register_android_product_rpc.sql` — Unknown product registration & deduplication.
5. `20261114_record_recognition_correction_rpc.sql` — Recognition learning & alias corrections.
6. `20261114_receive_android_stock_rpc.sql` — Stock receiving & buy price adjustments.
7. `20261114_adjust_android_stock_rpc.sql` — Stock adjustments with reason codes & non-negative clamping.
8. `20261114_physical_stock_count_rpc.sql` — Physical inventory count sessions & reconciliation.
9. `20261114_financial_events_table.sql` — Financial event models & idempotency tables.
10. `20261116_record_android_financial_event_rpc.sql` — Financial synchronization RPC with Desktop accounting boundary.
11. `20261116_record_android_purchase_rpc.sql` — Confirmed purchase receipts & new product registration.
12. `20261116_record_android_service_rpc.sql` — Service operations (printing, scanning, cyber) + consumable deductions.
13. `20261116_update_android_order_status_rpc.sql` — Storefront order status transitions & audit trail.
14. `20261116_convert_android_order_to_sale_rpc.sql` — Atomic order-to-sale conversion & double-conversion guard.
15. `20261116_rbac_and_rls_enforcement.sql` — Staff roles, RLS policies across all operational tables, and RPC authorization.
16. `20261116_android_audit_events_rpc.sql` — Immutable audit trail & role-scoped remote inspection.

---

## 3. Server-Side Supabase Edge Functions

The system includes two serverless Edge Functions located in `supabase/functions/` powered by the modern `@google/genai` TypeScript SDK:
- **`recognize-product`:** Server-side AI visual recognition fallback when local on-device OCR and perceptual hashing return `UNIDENTIFIED`. Constrained strictly to active catalogue IDs with cost-control caching.
- **`extract-receipt`:** Server-side purchase receipt OCR/AI extraction returning supplier, items, quantities, and totals as an unconfirmed draft requiring human review.

*Security Invariant:* `GEMINI_API_KEY` is kept strictly server-side in Supabase Secrets (`supabase secrets set GEMINI_API_KEY=...`) and is never embedded in client binaries.

---

## 4. Building the Android Application (`app-debug.apk`)

### Option A — Automated GitHub Actions CI (Recommended)
1. Push this repository to GitHub (`main` or `master` branch).
2. Open **GitHub Actions** → select workflow **"Build Emofah Android Debug APK"** (`.github/workflows/android-apk.yml`).
3. Click **Run workflow**.
4. The workflow will setup JDK 17, Android SDK 34, and Gradle 8.9, execute all 45 unit test suites, compile `app-debug.apk`, verify APK integrity and manifest metadata, and upload it as a downloadable artifact: **`emofah-android-debug`**.

### Option B — Local Android Studio / CLI Build
1. **Prerequisites:**
   - JDK 17 (Temurin recommended).
   - Android SDK Platform 34 (Build-Tools 34.0.0, Min SDK 26 / Android 8.0+).
   - Gradle 8.9.
2. **Configuration:**
   - Copy `android/local.properties.example` to `android/local.properties`.
   - Set `sdk.dir=/path/to/your/android/sdk`.
   - Supabase URL and anon key are pre-configured with fallback defaults in `android/app/build.gradle.kts`.
3. **Compile & Test:**
   ```bash
   cd android
   ./gradlew testDebugUnitTest assembleDebug
   ```
4. **Generated Output APK:**
   - `android/app/build/outputs/apk/debug/app-debug.apk`
5. **Install to Device:**
   ```bash
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 5. Mobile App Feature Map & Permissions

| Screen | Core Capabilities | Hardware / System Permissions |
| :--- | :--- | :--- |
| **Home** | Action-first cockpit, today's revenue, pending sync counter, quick shortcuts. | None |
| **Scan** | CameraX live viewfinder, framing guide, tap-to-focus, offline blur/light analysis, ML Kit OCR, on-device `dHash` candidate matching, cloud fallback. | `android.permission.CAMERA` |
| **Record Sale** | Single-product quantity stepper, price override editor (role-gated), payment chips (Cash, M-Pesa, Bank, Invoice), receipt generation. | None |
| **Stock** | Authoritative catalogue search, stock levels, stock movement history, stock receiving, stock adjustment, physical stock count sessions. | None |
| **Money** | M-Pesa SMS monitoring & parser, manual cash/expense logging, daily activity metrics, Desktop accounting separation (`PENDING_DESKTOP_RULES`). | `RECEIVE_SMS`, `READ_SMS` *(Optional, explicit opt-in only)* |
| **Orders** | Storefront order feed, order lifecycle management (`Confirm`, `Mark Ready`, `Cancel`), atomic `Convert to Sale & Deduct Stock` fulfillment. | None |
| **Activity** | Synchronisation queue status, manual flush trigger, conflict review, Today's Recorded Sales history, supervisor reversal, immutable audit trail. | `POST_NOTIFICATIONS` *(Android 13+ alerts)*, `BLUETOOTH_CONNECT` *(Thermal printing)* |

---

## 6. Testing & Quality Assurance (45 Unit Test Suites)

All test suites are located in `android/app/src/test/java/com/emofah/agencies/`:
- **Navigation:** `EmofahNavigationTest`.
- **Phase 1 (Recognition):** `RecognitionReferenceFoundationTest`, `CapturedImageQualityTest`, `RecognitionRankerTest`, `CloudRecognitionStage14Test`, `ProductRegistrationStage15Test`, `RecognitionLearningStage16Test`, `ImageRecognitionStage101Test`.
- **Phase 2 (Sales):** `SaleCalculatorTest`, `SaleTransactionStage22Test`, `SaleSpeedAndOverrideStage23Test`, `SaleHistoryCorrectionStage24Test`, `SalesTestingStage102Test`.
- **Phase 3 (Offline Sync):** `RoomDatabaseContractTest`, `CatalogueCacheStage32Test`, `SyncQueueStage33Test`, `BackgroundSyncStage34Test`, `ConflictHandlingStage35Test`, `OfflineStatusStage36Test`, `ReliabilityRecoveryStage94Test`, `OfflineTestingStage103Test`.
- **Phase 4 (Stock):** `StockLookupStage41Test`, `StockReceivingStage42Test`, `StockAdjustmentStage43Test`, `PhysicalStockCountStage44Test`, `StockProductRegistrationStage45Test`, `StockAlertsStage46Test`.
- **Phase 5 (Financial):** `FinancialEventFoundationStage51Test`, `MpesaMessageMonitoringStage52Test`, `MpesaParserValidationStage53Test`, `FinancialEventSyncStage55Test`, `FinancialTestingStage104Test`.
- **Phase 6 (Orders, Purchases & Services):** `OnlineOrderRetrievalStage61Test`, `OrderManagementStage62Test`, `OrderToSaleConversionStage63Test`, `ReceiptExtractionStage61Test`, `PurchaseSubmissionStage62Test`, `ServiceOperationsStage63Test`.
- **Phase 7 (Receipts & Notifications):** `DigitalReceiptStage71Test`, `BluetoothPrintingStage72Test`, `NotificationsStage73Test`.
- **Phase 9 (Security & Audit):** `AuthSessionStage91Test`, `RolePermissionsStage92Test`, `AuditAccountabilityStage93Test`.
- **Phase 10 (Cross-Platform):** `CrossPlatformStage105Test`.

---

## 7. Handover Checklist & Next Steps

1. **Database:** Open Supabase Dashboard SQL Editor and verify all 16 migrations from `USER_SETUP_CHECKLIST.md` are executed.
2. **Edge Functions:** Deploy `recognize-product` and `extract-receipt` via Supabase CLI and set `GEMINI_API_KEY`.
3. **Staff Roles:** Create staff user accounts in Supabase Auth and configure their roles in `public.android_staff_roles`.
4. **Build APK:** Run the GitHub Actions workflow or local Gradle build to produce `app-debug.apk`.
5. **Install & Run:** Install `app-debug.apk` on physical Android devices.
