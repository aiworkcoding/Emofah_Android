# Emofah Agencies — User Setup & Deployment Checklist

> **Keep this file as your step-by-step checklist.** Every time we complete a phase/stage in code, the database migrations, secrets, and Android APK steps you need to run on your side are listed here so nothing is missed.

---

## 1. Supabase Database Setup (Do Once + When New Migrations Are Added)

Because AI Studio does not hold your Supabase SQL Editor admin session, **you must run the SQL migrations in your Supabase Dashboard**:

1. Open **Supabase Dashboard** → Project `nbmmfdiqywmkjaltezro` → **SQL Editor** → **New Query**.
2. Run the migrations:

### Option A — Fastest (Run All 16 Migrations in 1 Single Step — 3 Seconds!)
- [ ] Open **`supabase/COMPLETE_EMOFAH_BACKEND_SETUP.sql`** (or click **"Copy Complete Setup SQL"** in the web app under **Backend Setup**).
- [ ] Paste into Supabase SQL Editor and click **Run**.
- [ ] All 16 migrations, tables, RPCs, RLS rules, and immutability triggers are applied atomically!

### Option B — Run Individual Migrations In Order
If you prefer running migration files one-by-one:

- [ ] **0.** `supabase/migrations/20261113_foundational_schema.sql` *(Foundational base schema: products, sales, orders, stock_movements, expenses, settings with IF NOT EXISTS — ensures relations exist without touching any past data)*
- [ ] **1.** `supabase/migrations/20261114_record_android_sale_rpc.sql` *(Idempotent one-touch Android sale RPC & table)*
- [ ] **2.** `supabase/migrations/20261114_record_android_sale_price_override.sql` *(Authorised price override columns & role validation)*
- [ ] **3.** `supabase/migrations/20261114_reverse_android_sale_rpc.sql` *(Safe supervisor sale reversals & audit trail)*
- [ ] **4.** `supabase/migrations/20261114_register_android_product_rpc.sql` *(Unknown product registration RPC & duplicate guard)*
- [ ] **5.** `supabase/migrations/20261114_record_recognition_correction_rpc.sql` *(Recognition alias & correction learning RPC)*
- [ ] **6.** `supabase/migrations/20261114_receive_android_stock_rpc.sql` *(Stock receiving RPC & ledger movements)*
- [ ] **7.** `supabase/migrations/20261114_adjust_android_stock_rpc.sql` *(Stock adjustments, reason codes & non-negative clamping)*
- [ ] **8.** `supabase/migrations/20261114_physical_stock_count_rpc.sql` *(Physical stock count sessions & variance reconciliation — **re-run once** if you ran an earlier copy, as RPC grants and `stock_movements` guard were added)*
- [ ] **9.** `supabase/migrations/20261114_financial_events_table.sql` *(Android financial events & idempotency tables — Stage 5.1)*
- [ ] **10.** `supabase/migrations/20261116_record_android_financial_event_rpc.sql` *(Idempotent financial event & M-Pesa sync RPC with Desktop-managed classification)*
- [ ] **11.** `supabase/migrations/20261116_record_android_purchase_rpc.sql` *(Confirmed purchase receipt submission, new resale product registration & shared inventory update RPC)*
- [ ] **12.** `supabase/migrations/20261116_record_android_service_rpc.sql` *(Service operations RPC for Printing, Photocopying, Scanning, Internet browsing & E-Gov assistance + linked consumable stock deduction)*
- [ ] **13.** `supabase/migrations/20261116_update_android_order_status_rpc.sql` *(Stage 6.2 idempotent order status transitions & audit log for shared public.orders)*
- [ ] **14.** `supabase/migrations/20261116_convert_android_order_to_sale_rpc.sql` *(Stage 6.3 atomic order-to-sale conversion RPC creating real public.sales rows, deducting products.qty, and preventing double-selling)*
- [ ] **15.** `supabase/migrations/20261116_rbac_and_rls_enforcement.sql` *(Stage 9.2 `public.android_staff_roles`, RLS enforcement across `android_*` tables, `get_my_android_staff_profile()`, and server-side Supervisor/Owner-Manager authorization inside privileged RPCs)*
- [ ] **16.** `supabase/migrations/20261116_android_audit_events_rpc.sql` *(Stage 9.3 append-only `public.android_audit_events`, immutability trigger, idempotent batch upload RPC `record_android_audit_events`, and role-scoped `get_android_audit_events`)*

3. Assign Android Staff Roles & Verify Storage:
- [ ] In **Supabase Dashboard → Authentication → Users**, create staff accounts (`CASHIER`, `SUPERVISOR`, `OWNER_MANAGER`), then insert/update their authoritative roles in `public.android_staff_roles` via SQL Editor (e.g. `insert into public.android_staff_roles (user_id, email, full_name, staff_role) values ('<auth-user-uuid>', 'supervisor@emofah.co.ke', 'Supervisor Name', 'SUPERVISOR');`).
- [ ] Open **Storage** in Supabase Dashboard and confirm `product-images` exists and is **Public** (used for product reference photos).

---

## 2. Supabase Edge Function Setup (Cloud Product Recognition & Receipt OCR/AI)

Used for:
- `recognize-product`: online AI product matching when local on-device recognition returns "Unidentified".
- `extract-receipt`: online AI receipt extraction (supplier, items, quantities, unit costs, totals) returning an unconfirmed draft for human review.

1. Install/login to Supabase CLI locally and link project `nbmmfdiqywmkjaltezro`.
2. Set your Gemini API key securely on the server (**never** inside the Android app):
   ```bash
   supabase secrets set GEMINI_API_KEY=your_gemini_api_key
   ```
3. Deploy the edge functions:
   ```bash
   supabase functions deploy recognize-product
   supabase functions deploy extract-receipt
   ```

---

## 3. Supabase Credentials in the Android App (Already Pre-Configured)

- `android/app/build.gradle.kts` now includes fallback defaults for your publishable `SUPABASE_URL` and `SUPABASE_ANON_KEY`, so **you do NOT need to touch environment variables** just to build.
- Never put `SUPABASE_SERVICE_ROLE_KEY` or `GEMINI_API_KEY` into `android/local.properties` or Android source code.

---

## 4. Android Build Dependencies & Generating the `.apk`

AI Studio runs in a Linux web container without JDK 17 / Android SDK / `adb`, so compile the real `.apk` using **either** Option A or Option B:

### Option A — GitHub Actions CI (Easiest, No Local Android Studio Required)
- [ ] Push this repo to GitHub (`main` or `master` branch).
- [ ] Open **GitHub Repo → Actions → "Build Emofah Android APK" → Run workflow** (uses `.github/workflows/android-apk.yml` with JDK 17, Android SDK 34, Gradle 8.9).
- [ ] Download the generated artifact **`emofah-agencies-debug-apk`** (`app-debug.apk`).

### Option B — Local Android Studio / CLI
- [ ] Install **JDK 17** and **Android SDK Platform 34** (Min SDK 26 / Android 8.0+).
- [ ] Copy `android/local.properties.example` → `android/local.properties` and set `sdk.dir=/path/to/your/Android/sdk`.
- [ ] Let Gradle sync dependencies (Compose BOM `2024.10.01`, Room `2.6.1` + KSP `2.0.21-1.0.27`, WorkManager `2.9.1`, CameraX `1.4.0`, ML Kit Text Recognition `16.0.1`).
- [ ] Build & run unit tests:
  ```bash
  cd android
  gradle testDebugUnitTest assembleDebug
  ```
- [ ] Output APK path: `android/app/build/outputs/apk/debug/app-debug.apk`

---

## 5. Installing & Testing on Your Android Phone

- [ ] Install via USB (`adb install -r android/app/build/outputs/apk/debug/app-debug.apk`) OR transfer `app-debug.apk` to your phone and allow **"Install unknown apps"**.
- [ ] **Permissions on device:**
  - Grant **Camera** when tapping **Scan**.
  - In **Money**, toggle **M-Pesa SMS Monitoring** ON only if you want automatic local `MPESA` SMS capture (you can also paste M-Pesa SMS or log cash/expenses manually without granting SMS permission).
  - For **Bluetooth Receipt Printing** (58mm/80mm thermal POS printers): pair your printer once in Android Bluetooth Settings, then grant **Nearby Devices / Bluetooth** when tapping **Print Receipt** or **Select** inside any Digital Receipt preview.
  - For **Operational Notifications** (new online orders, sync holds/conflicts, stock alerts): on Android 13+, open **Activity → Sync Queue → Notifications & Operational Alerts** and tap **Allow Notifications**.
