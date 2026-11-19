# Notebook Runner

Aplikasi Android native untuk membuka, mengedit, dan menjalankan notebook Jupyter (`.ipynb`) dari ponsel. Eksekusi dilakukan di cloud melalui **Kaggle API** atau **Google Colab API**, lengkap dengan opsi keep-alive agar sesi Colab tidak cepat menganggur.

Dibangun dengan Kotlin, Jetpack Compose, OkHttp, dan kotlinx.serialization. Tanpa server perantara: kredensial dan notebook tetap di perangkat Anda.

## Fitur

- Impor dan ekspor file `.ipynb` lewat file picker Android.
- Editor cell kode dan markdown dengan penyimpanan otomatis.
- Render output notebook: teks/stream, error + traceback, gambar PNG/JPEG, HTML (WebView dengan JavaScript dimatikan), dan JSON.
- **Kaggle**: unggah kernel, pantau status, unduh output notebook, lalu gabungkan output ke cell lokal berdasarkan source.
- **Colab**: OAuth 2.0 + PKCE, pembuatan/penggunaan ulang runtime, eksekusi cell satu per satu lewat WebSocket Jupyter, output tampil langsung.
- **Keep-alive dua lapis** untuk Colab:
  1. Cell Python tambahan yang menjalankan loop background dan menulis heartbeat setiap 45 detik.
  2. Heartbeat `kernel_info_request` dari aplikasi setiap 45 detik setelah semua cell selesai, selama mode "jaga sesi" aktif.
- Foreground service + wake lock parsial agar proses tetap hidup saat aplikasi di background.
- Log eksekusi, salin log, hentikan run, dan hentikan runtime Colab.
- Kredensial dienkripsi dengan Android Keystore (AES-GCM) dan dikecualikan dari backup cloud.
- UI Bahasa Indonesia, tema gelap/terang.

## Tangkapan layar

Belum disertakan. Jalankan aplikasi di emulator/perangkat untuk melihat antarmuka.

## Arsitektur

```
app/src/main/java/com/adekarya/ipynbrunner/
├── MainActivity.kt              # Compose host + file picker + snackbar
├── AppViewModel.kt              # state UI, library notebook, aksi pengguna
├── RunnerApp.kt                 # Application + dependency container
├── data/
│   ├── model/Notebook.kt        # parser/writer .ipynb, model cell, keep-alive cell
│   ├── model/Backend.kt         # opsi Kaggle/Colab, spesifikasi runtime
│   ├── kaggle/KaggleClient.kt   # Kaggle API v1
│   ├── colab/ColabOAuth.kt      # authorization code + PKCE, loopback 127.0.0.1
│   ├── colab/ColabApi.kt        # Colab API v1beta: runtime, operation, specs
│   ├── colab/JupyterProtocol.kt # Jupyter messaging 5.3 + output collector
│   ├── colab/JupyterClient.kt   # REST kernel + WebSocket channels
│   ├── run/RunController.kt     # orkestrasi run, event, keep-alive, cancel
│   ├── run/RunService.kt        # foreground service & notifikasi
│   ├── NotebookStore.kt         # library file privat + recent notebooks
│   ├── SettingsStore.kt         # preferensi non-rahasia
│   ├── SecureStore.kt           # Keystore AES-GCM
│   └── network/Http.kt          # helper OkHttp + error message
└── ui/                          # Home, Notebook, Settings, sheet, renderer
```

Alur eksekusi:

- **Kaggle** — `POST /api/v1/kernels/push` → poll `/kernels/status` → `/kernels/output` → unduh `.ipynb` → merge output. Fallback ke `/kernels/pull`.
- **Colab** — refresh OAuth token → buat/pakai runtime → `POST /api/kernels` → WebSocket `/api/kernels/{id}/channels` → `execute_request` per cell → output iopub → opsional keep-alive.

## Build

Persyaratan:

- JDK 17
- Android SDK Platform 35 dan Build-Tools 35.0.0
- Gradle wrapper sudah disertakan (Gradle 8.11.1)

```bash
git clone <repo-url>
cd ipynb-runner-android

# Setelah mengarahkan ANDROID_HOME / local.properties ke SDK Anda:
./gradlew assembleDebug
```

APK debug dihasilkan di:

```
app/build/outputs/apk/debug/app-debug.apk
```

Menjalankan unit test:

```bash
./gradlew testDebugUnitTest
```

Atau buka folder proyek di Android Studio (Ladybug/Meerkat atau lebih baru) dan jalankan konfigurasi `app`.

Konfigurasi:

- `applicationId` / namespace: `com.adekarya.ipynbrunner`
- minSdk 26, compileSdk/targetSdk 35
- Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.12.01

## Setup Kaggle

1. Masuk ke [kaggle.com](https://www.kaggle.com), buka **Settings → API**, klik **Create New Token**.
2. File `kaggle.json` berisi `username` dan `key`. Salin keduanya ke **Pengaturan → Kaggle** di aplikasi.
3. Pastikan akun sudah memenuhi syarat untuk GPU/internet jika opsi tersebut dipakai (misalnya verifikasi nomor telepon untuk GPU).
4. Saat menjalankan, tentukan **judul kernel** dan **slug**. Jika kernel dengan slug tersebut belum ada, Kaggle API akan membuatnya; jika sudah ada, versi baru akan diunggah.
5. Notebook diunggah dengan output lokal dibersihkan. Setelah selesai, aplikasi mengunduh output `.ipynb` dan mencocokkan cell berdasarkan source; jika source berubah, output dipetakan berdasarkan urutan cell kode.

Catatan:

- API key Kaggle memberikan akses ke akun Anda. Jangan membagikannya.
- Kuota CPU/GPU dan batas runtime ditentukan oleh Kaggle.
- Hanya kernel Python (`language: python`) yang didukung jalur ini.

## Setup Google Colab

Colab API memerlukan OAuth milik Anda sendiri. Aplikasi tidak membawa client secret global.

1. Buka [Google Cloud Console](https://console.cloud.google.com), buat/pilih project.
2. Aktifkan **Colab API** (`colaboratory.googleapis.com`) di **APIs & Services → Library**.
3. Konfigurasikan **OAuth consent screen**:
   - Tipe **External** (atau Internal jika memakai Google Workspace).
   - Tambahkan scope `https://www.googleapis.com/auth/colaboratory` (dan `openid email profile`).
   - Tambahkan akun Anda sebagai **test user** selama aplikasi masih berstatus Testing.
4. Buat **Credentials → Create credentials → OAuth client ID**, pilih tipe **Desktop app**.
5. Salin **Client ID** dan **Client secret** ke aplikasi.
6. Isi **Quota project** dengan project ID Google Cloud yang sama. Nilai ini dikirim sebagai header `x-goog-user-project`.
7. Tekan **Hubungkan**. Aplikasi membuka Custom Tab, menangkap callback di `http://127.0.0.1:<port>/` (loopback sesuai aturan OAuth desktop), lalu menukar authorization code + PKCE menjadi token.
8. Kembali ke aplikasi. Token akses/refresh disimpan terenkripsi.

Catatan penting:

- Saat OAuth consent screen berstatus **Testing**, refresh token Google biasanya berlaku terbatas (sekitar 7 hari). Login ulang bila token kedaluwarsa.
- Scope `colaboratory` adalah scope sensitif/terbatas. Untuk penggunaan publik di luar test user, Google dapat mewajibkan verifikasi aplikasi.
- Pastikan billing/kuota project dalam kondisi baik. Error `403` biasanya berarti API belum aktif, scope belum disetujui, akun bukan test user, atau quota project salah.
- Runtime dibuat dengan `runtimeId` acak dan disimpan di perangkat agar bisa dipakai ulang pada run berikutnya. Runtime tidak otomatis dihapus saat run selesai; gunakan **Pengaturan → Hentikan runtime** jika tidak dipakai.

## Keep-alive: cara kerja dan batasnya

Keep-alive di aplikasi ini **best-effort**, bukan jaminan sesi abadi.

1. **Cell keep-alive di kernel** — sebelum cell notebook dijalankan, aplikasi mengirim cell Python tambahan (tidak mengubah file `.ipynb` Anda) yang memulai daemon thread:

   ```python
   while True:
       time.sleep(45)
       # tulis file penanda + print(...)
   ```

   Thread ini menghasilkan aktivitas dan output berkala. Cell ini juga bisa disisipkan permanen lewat menu notebook **Sisipkan cell keep-alive**.

2. **Heartbeat aplikasi** — jika opsi "Jaga sesi setelah selesai" aktif, setelah semua cell selesai aplikasi tetap membuka WebSocket kernel dan mengirim `kernel_info_request` setiap 45 detik. Status berubah menjadi **Keep-alive** dengan tombol **Akhiri**.

3. **Foreground service** — notifikasi "Eksekusi notebook" dan wake lock parsial menjaga proses Android tetap hidup saat layar mati atau aplikasi di background.

Yang tetap bisa menghentikan sesi:

- Kuota CPU/GPU habis atau runtime di-preempt oleh Google.
- Batas waktu sesi, kebijakan idle, atau perubahan aturan Colab/Kaggle.
- Koneksi internet terputus lebih dari beberapa heartbeat (aplikasi berhenti setelah 3 kegagalan berturut-turut).
- Proses aplikasi di-force stop oleh sistem/pengguna, atau perangkat reboot.
- Runtime dihentikan dari sisi Google Colab.

Gunakan keep-alive secara wajar dan patuhi Terms of Service Google Colab. Jangan memakainya untuk menyalahgunakan kuota atau menjalankan beban kerja terlarang.

## Keamanan

- API key Kaggle, client secret OAuth, dan token akses/refresh disimpan menggunakan **Android Keystore** dengan AES-GCM 256-bit.
- File SharedPreferences rahasia (`secrets.xml`) dikecualikan dari Auto Backup dan device transfer.
- Notebook disimpan sebagai file privat di direktori internal aplikasi; ekspor dilakukan lewat Storage Access Framework.
- Renderer HTML memakai WebView dengan JavaScript, akses file, dan akses konten dimatikan, serta pembatasan pemuatan jaringan.
- Tidak ada telemetri atau server pihak ketiga. Permintaan hanya ke `kaggle.com` dan `*.googleapis.com` / runtime Colab.

## Keterbatasan

- Satu proses run aktif dalam satu waktu. Notebook lain tidak bisa dibuka selama run berjalan.
- Eksekusi Colab berjalan berurutan dan bergantung pada koneksi WebSocket; belum ada resume otomatis setelah koneksi putus.
- Widget interaktif (`ipywidgets`) dan output JavaScript tidak interaktif. HTML statis ditampilkan, skrip tidak dijalankan.
- Output Kaggle digabungkan berdasarkan source/urutan; perubahan struktur cell yang ekstrem dapat membuat pemetaan meleset.
- Notebook sangat besar (>25 MB) ditolak saat impor.
- Tidak ada sinkronisasi otomatis ke Google Drive/Kaggle Dataset.
- Membatalkan run Kaggle hanya menghentikan polling di aplikasi; kernel yang sudah diunggah dapat terus berjalan di server Kaggle.
- Build ini hanya dikonfigurasi untuk debug; belum ada penandatanganan rilis atau Play Store packaging.
- Tampilan output HTML memakai tinggi tetap dan scroll internal; tabel lebar dapat di-scroll horizontal.

## Referensi API

- Kaggle API: <https://www.kaggle.com/docs/api> dan <https://github.com/Kaggle/kaggle-api>
- Google Colab API: <https://developers.google.com/colab>
- Google OAuth untuk desktop/loopback: <https://developers.google.com/identity/protocols/oauth2/native-app>
- Jupyter messaging: <https://jupyter-client.readthedocs.io/en/stable/messaging.html>

## Lisensi

MIT — lihat [LICENSE](LICENSE).

## Disclaimer

Proyek ini tidak berafiliasi dengan Kaggle atau Google. "Kaggle", "Colab", dan "Google" adalah merek milik pemiliknya masing-masing. Anda bertanggung jawab atas penggunaan API, kuota, dan kepatuhan terhadap ketentuan layanan.
