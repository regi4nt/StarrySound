# StarrySound

Aplikasi equalizer, audio enhancer, dan pemutar musik ringan untuk Android, mirip FX Sound.
Minimum Android 8.0 (API 26), target ukuran APK ~2-4 MB, RAM footprint kecil
(tidak ada polling loop, tidak ada library berat).

## Kenapa ini source code, bukan file .apk langsung?

Project ini dibuat lewat sesi chat berbasis browser yang **tidak punya Android
SDK / Gradle Android plugin terpasang**, dan akses jaringannya dibatasi
(tidak bisa mengunduh dari Google Maven / `dl.google.com`). Jadi saya tidak
bisa meng-compile APK di sini. Yang saya berikan adalah source project
lengkap dan siap-build.

## Cara build via GitHub Actions (gratis, tanpa Android Studio)

Repo publik dapat runner gratis tanpa batas wajar; repo private dapat kuota
gratis ~2000 menit/bulan (akun biasa) — build project ini cuma butuh
beberapa menit sekali build, jadi sangat cukup.

1. Buat repo baru di GitHub (public atau private, bebas).
2. Push seluruh isi folder `StarrySound` ini ke repo tersebut:
   ```bash
   cd StarrySound
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/USERNAME/NAMA_REPO.git
   git push -u origin main
   ```
3. Buka tab **Actions** di repo GitHub kamu → workflow **"Build APK"** akan
   otomatis jalan setelah push (atau klik **Run workflow** manual kalau
   tidak otomatis).
4. Tunggu sampai selesai (centang hijau, ~3-5 menit).
5. Buka run yang selesai tadi → scroll ke bagian **Artifacts** di bawah →
   download `StarrySound-debug` (APK siap install & testing) atau
   `StarrySound-release-unsigned` (APK release tapi belum ditandatangani,
   perlu di-sign dulu sebelum dipakai jangka panjang — lihat catatan
   signing di bawah).
6. Extract zip artifact tsb, dapat file `.apk`, pindahkan ke HP dan install
   (aktifkan "Install dari sumber tidak dikenal" dulu di HP).

**Catatan signing APK release:** APK release dari CI di atas *unsigned* —
Android tidak akan menginstalnya sebelum ditandatangani. Untuk pemakaian
sehari-hari, pakai APK **debug** saja (sudah otomatis signed dengan debug
key, langsung bisa diinstal). Kalau nanti mau publish serius / signed
release, tambahkan keystore + secrets GitHub (`signingReport` di
`build.gradle`) — bilang saja kalau mau saya siapkan langkah itu juga.

## Cara build lokal (di laptop kamu, Ryzen 7 / RAM 4GB lebih dari cukup)

1. Install **Android Studio** (Giraffe/Koala ke atas) — gratis.
2. `File > Open` folder `StarrySound` ini.
3. Tunggu Gradle sync selesai (butuh internet, otomatis download SDK yang
   dibutuhkan).
4. Sambungkan HP Android (USB debugging aktif) atau pakai emulator API 26-30.
5. Klik Run (▶). Untuk rilis: `Build > Generate Signed Bundle / APK`.

> Catatan: folder `gradle/wrapper` di project ini belum berisi
> `gradle-wrapper.jar` (file biner, tidak bisa saya buat dari sandbox ini).
> Saat pertama kali dibuka, Android Studio akan otomatis menawarkan
> "Use Gradle from 'wrapper'" — klik OK / biarkan Android Studio
> men-generate ulang wrapper-nya secara otomatis. Ini tidak menghalangi
> build via GitHub Actions di atas karena workflow-nya memakai Gradle
> yang diinstal langsung oleh runner, bukan wrapper.

Kalau laptop 4GB RAM terasa berat menjalankan Android Studio + emulator
sekaligus, testing langsung ke HP fisik via USB jauh lebih ringan
dibanding emulator.

## Install berdampingan dengan versi Lite

Versi 2.0 memakai **applicationId baru `com.starrysound.app`** (kode Kotlin tetap di
package `com.fxsoundlite.app` lewat `namespace`, itu hanya nama internal). Jadi Android
menganggapnya aplikasi terpisah: **tidak akan menimpa StarrySound Lite**, keduanya bisa
terpasang bersamaan, dan pengaturan/data masing-masing terpisah. Tips: matikan efek di
salah satunya agar tidak dua-duanya memasang equalizer ke output yang sama.

## Fitur

- **Quick** (tab di sebelah kanan Beranda): feed vertikal ala Reels. Tiap halaman satu lagu
  dengan foto sampulnya, dan yang diputar **hanya reff-nya** (klip ±25 detik). Saat klip habis
  feed otomatis turun ke lagu berikutnya; saat hampir habis, lagu baru dimuat. Tombol refresh
  (atau ketuk tab Quick lagi) mengganti semua lagu. Di pojok kanan: **putar full**, **tambah ke
  playlist**, **tambah ke antrean**, **unduh**, **bagikan**. Mini player disembunyikan di tab ini.
  Rekomendasi = "related videos" YouTube + pencarian artis favoritmu (dari lagu yang disukai /
  diputar) + penemuan acak. Tidak memakai akun YouTube. Posisi reff adalah **perkiraan**
  (YouTube tidak menyediakan penanda reff). Pratinjau memakai mesin sendiri (bukan `MusicService`),
  jadi antreanmu tidak terganggu; musik yang sedang main dijeda dan dilanjutkan saat keluar tab.
- **Pemutar musik** (baru di versi 2.0): tab **Musik** di navigasi bawah menampilkan
  semua lagu di perangkat (dibaca dari MediaStore, tanpa menyalin file). Ada pencarian
  (judul/artis/album), play/pause, sebelumnya/berikutnya, seek bar, **acak** (shuffle) dan
  **ulangi** (mati / semua / satu lagu). Pemutaran berjalan di `MusicService` (foreground
  service), jadi tetap jalan saat aplikasi ditutup, dengan notifikasi media, kontrol di
  layar kunci, tombol headset, audio focus (jeda saat ada telepon) dan jeda otomatis saat
  headphone dicabut. Equalizer/bass/surround berlaku ke musik lokal dengan memasang efek ke sesi audio
  pemutar itu sendiri selama lagu diputar (banyak perangkat mengabaikan efek sesi global/0);
  saat jeda, berhenti, atau memutar YouTube, efek kembali ke output global.
  Ambience (reverb) selalu tetap di output global. Butuh izin audio
  (`READ_MEDIA_AUDIO` di Android 13+, `READ_EXTERNAL_STORAGE` di bawahnya), diminta saat
  tab Musik dibuka dan tombol "Izinkan akses" ditekan.

- **Sumber musik online: YouTube**: di tab **Musik** ada dua tab sumber —
  **Perangkat** dan **YouTube**. Pencarian YouTube dijalankan lewat
  API key yang diisi di **Settings > Musik online**, hasilnya tampil di daftar yang sama.
  Semua kontrol (putar/jeda, sebelumnya/berikutnya, seek, acak, ulangi) ada di halaman pemutar
  ini, bukan di Screensaver. Semua sumber memakai satu `MusicService`, jadi pemutaran **tetap
  jalan di latar belakang** dengan **notifikasi media + kontrol di layar kunci/headset**
  seperti saat memutar YouTube versi web. Setup di bawah.

- **Deteksi lagu** (tile di Beranda, menggantikan tombol Screensaver; Screensaver tetap ada di tab
  navigasi bawah). Ketuk mik; ada tiga mode:
  - **Nyanyi**: lirik yang kamu nyanyikan dikenali pengenal suara sistem (pilih bahasa Otomatis /
    Indonesia / English), lalu dicari di YouTube. Teks hasil dengar bisa diedit sebelum dicari ulang.
  - **Senandung**: klip ±12 detik dicocokkan lewat melodi memakai **ACRCloud**; butuh host, access key,
    dan access secret di Settings > Musik online > Deteksi lagu.
  - **Lagu di sekitar**: merekam lagu yang sedang diputar lalu mengenalinya lewat endpoint publik
    Shazam, **tanpa key** (`ShazamFingerprint.kt` membuat sidik jarinya, `ShazamRecognizer.kt` mengirimnya).
    Tidak mengenali senandung, dan karena tidak resmi, Shazam bisa mengubah/memblokirnya kapan saja.
  Ketuk hasil untuk memutar. Butuh izin mikrofon.
- **Equalizer** multi-band (jumlah band tergantung chip device, biasanya 5)
- **Bass Boost**, **Virtualizer (surround)**, **Loudness Enhancer**
- 7 preset (Flat, Music, Bass Boost, Vocal, Rock, Movie, Small Speaker)
- Berjalan lewat **foreground service** kecil agar efek tetap aktif walau
  layar aplikasi ditutup
- Auto re-apply setelah reboot (`BootReceiver`)
- Notifikasi punya tombol **Matikan**: efek audio langsung mati dan notifikasi hilang.
  Nyalakan lagi lewat aplikasi atau widget
- UI bilingual: Indonesia & English, dengan pemilih bahasa manual di layar
  **Settings** (tombol ⚙ di kanan atas layar utama) — pilihan "Ikuti sistem",
  "English", atau "Bahasa Indonesia", tidak perlu ganti locale sistem HP
- Layar **Settings** terpisah berisi pemilih bahasa dan **Mode Root**
  (dipindah dari section "Advanced" di layar utama sebelumnya)
- **Auto screensaver saat mengisi daya** (Settings, default mati): saat charger
  dicolok atau layar menyala dalam keadaan mengisi daya *dan* HP terkunci,
  Screensaver terbuka di atas layar kunci (`ChargingScreensaverActivity`).
  Sentuh layar atau cabut charger untuk menutup; HP tetap terkunci. Dibuka lewat
  notifikasi layar penuh, jadi perlu izin **Notifikasi** dan, di Android 14+,
  izin **Notifikasi layar penuh** (Settings menuntun pengguna ke halaman izinnya)

## Preset EQ otomatis per output

Di layar **Equalizer**, kartu **Preset otomatis per output** (mati secara bawaan). Kalau dinyalakan, tiga output
punya preset masing-masing: **Speaker**, **Headphone (jack / USB)**, dan **Bluetooth** (Bluetooth menang kalau
tersambung bersamaan dengan kabel). Saat output berganti (headphone dicolok/dicabut, Bluetooth tersambung/putus),
app otomatis menerapkan preset output itu.

- Cara mengatur: pilih preset seperti biasa saat output itu aktif (chip, widget, atau tombol notifikasi),
  maka preset itu diingat untuk output tersebut. Atau ketuk barisnya dan pilih langsung.
- **Tidak diubah** = output itu tidak memicu pergantian preset.
- Preset yang sudah aktif tidak diterapkan ulang, jadi geseran slider manual tidak tertimpa oleh event perangkat lain.
- Deteksi berjalan di `EffectService` (`AudioDeviceCallback`); logikanya ada di `OutputPresets.kt`.

## Login Google & cadangan ke Google Drive

Di **Settings > Akun & cadangan** ada tombol **Masuk dengan Google**. Setelah masuk, playlist,
lagu favorit, riwayat YouTube, dan statistik dengar dicadangkan ke Google Drive milikmu sendiri
(folder aplikasi tersembunyi `appDataFolder`, scope `drive.appdata`; file Drive lain tidak
pernah dibaca). API key (YouTube / ACRCloud) sengaja **tidak** ikut diunggah.

- **Lagu perangkat tidak ikut dicadangkan** (bawaan). Hanya playlist/favorit berisi YouTube & radio yang diunggah;
  lagu dari penyimpanan HP tetap di HP. Saklar **Cadangkan lagu perangkat** menyertakannya (dicocokkan lewat judul + artis).
- **Impor dari Drive**: tombol manual, satu arah (gabung ke data di HP, tidak mengunggah apa pun).
- **Sinkron otomatis** (mati secara bawaan, bisa dinyalakan): unggah ±20 detik setelah ada perubahan, dan tarik data terbaru saat app dibuka.
- **Sinkronkan sekarang**: tombol manual. Kalau perangkat lain sudah mengubah data dan perangkat ini
  juga berubah, kedua sisi **digabung** (union), bukan ditimpa.
- **Keluar**: hanya memutus akun di app; data lokal dan cadangan di Drive tetap ada.

### Setup satu kali di Google Cloud Console (wajib, kalau tidak login gagal kode 10 / 12500)

1. Buat project di https://console.cloud.google.com lalu aktifkan **Google Drive API**.
2. **APIs & Services > OAuth consent screen**: isi nama app, email; tambahkan scope
   `.../auth/drive.appdata`. Selama status *Testing*, tambahkan email kamu di **Test users**
   (scope ini tidak sensitif, jadi bisa di-*Publish* tanpa verifikasi).
3. **Credentials > Create credentials > OAuth client ID > Android**:
   - Package name: `com.starrysound.app`
   - SHA-1: `71:64:90:20:76:A9:E1:F8:0F:47:60:D7:A2:53:9A:B9:3D:6A:17:29`
     (kunci `app/starrysound.jks` yang dipakai semua build, lokal maupun GitHub Actions).
4. Tidak ada client ID atau secret yang perlu ditempel di kode.

Kalau masuk gagal, app menampilkan dialog berisi **kode error, nama paket, dan SHA-1 APK yang sedang
berjalan** (tombol *Salin SHA-1*). Nilai itu harus sama persis dengan client Android di Cloud Console.
Kode 10 / 12500 = client tidak cocok; kalau project masih *Testing*, pastikan email kamu ada di *Test users*.
Perubahan di Cloud Console kadang butuh beberapa menit sampai berlaku.

### Tab Drive (musik dari Google Drive)

Di halaman Musik ada tab **Drive** tepat di kanan **Perangkat**. Isinya semua file audio di Google Drive-mu
(termasuk yang dibagikan ke kamu), bisa dicari lewat kotak pencarian, diputar, dan dimasukkan ke playlist.
Perlu masuk dulu di Settings > Google account. Saat tab dibuka pertama kali, Google meminta izin
**baca saja** (`drive.readonly`); tidak ada file yang diubah atau diunggah.

- Lagu diunduh dulu ke cache app lalu diputar dari sana (seek & equalizer jalan seperti lagu perangkat,
  putar ulang instan). Cache dibatasi 300 MB (yang paling lama tidak dipakai dihapus) dan dikosongkan saat keluar akun.
- Setup Cloud Console: tambahkan scope `.../auth/drive.readonly` di OAuth consent screen. Ini scope *restricted*:
  selama status *Testing* cukup tambahkan emailmu di Test users; untuk dipublikasikan ke umum perlu verifikasi Google.

## Rekomendasi musik AI (Home) — gratis

Rak **Pilihan AI untukmu** di Home memakai **Google Gemini (paket gratis)** untuk menyarankan lagu
dari seleramu: artis teratas, lagu favorit, dan riwayat putar. Tiap saran dicari di YouTube lalu
tampil sebagai kartu yang bisa langsung diputar (tekan lama = suka / tambah ke playlist).

- Ambil key gratis di https://aistudio.google.com/apikey (akun Google, tanpa kartu kredit), lalu
  tempel di **Settings > Musik online > Rekomendasi AI**. Tanpa key, rak hanya menampilkan petunjuk.
  Key disimpan di perangkat saja (tidak ikut cadangan Drive).
- Yang dikirim hanya nama artis dan judul lagu. Di paket gratis, Google boleh memakai data itu
  untuk meningkatkan produknya, jadi jangan dipakai bila itu keberatan.
- Hasil di-cache 6 jam; tombol refresh di kepala rak meminta saran baru. Kalau batas gratis
  tercapai (HTTP 429), tunggu beberapa menit.
- Model diatur lewat `AiRecommender.MODEL` (default alias `gemini-flash-latest`, selalu mengikuti
  Flash terbaru; nama model yang di-pin bisa dipensiunkan Google).
- Setiap refresh memicu sekitar 12 pencarian YouTube; dengan YouTube API key itu memakai
  kuota (~100 unit per pencarian), tanpa key dipakai NewPipeExtractor (tanpa kuota).

## Setup musik online (YouTube)

Tab YouTube menampilkan tombol yang mengarahkan ke langkah yang masih kurang.

### YouTube
1. Buka Google Cloud Console, buat project, aktifkan **YouTube Data API v3**, buat **API key**.
2. Tempel key di **Settings > Musik online > YouTube**. (Disarankan batasi key hanya untuk
   YouTube Data API v3.)
3. Cari lagu di tab YouTube lalu tekan **Cari** di keyboard. Satu pencarian memakai 101 unit dari
   kuota gratis 10.000 unit/hari (sekitar 99 pencarian per hari).

Cara kerja & batasan jujur:
- API key hanya untuk **mencari**. Pemutaran memakai **pemutar tersemat resmi YouTube (IFrame
  Player API)** di dalam WebView tersembunyi milik `MusicService` (foreground service), jadi tetap
  bunyi saat aplikasi ditutup/layar mati.
- Syarat layanan YouTube tidak menyukai pemutaran audio-saja/latar belakang dari pemutar tersemat,
  dan YouTube bisa mengubah perilakunya kapan saja. Ini bukan jalur "resmi" seperti YouTube
  Premium. Pakai untuk keperluan pribadi dan pahami risikonya.
- Video yang pemiliknya mematikan embed tidak bisa diputar; pencarian sudah memfilter
  `videoEmbeddable`, kalau tetap gagal aplikasi otomatis lompat ke lagu berikutnya.
- Pencarian dibatasi kategori **Musik** (ubah konstanta `CATEGORY` di `YouTubeApi.kt` jadi
  `null` untuk semua kategori).

## Mode Non-Root vs Root — apa bedanya SUNGGUHAN

**Non-root (default, jalan di HP mana pun Android 8+):**
Semua efek (EQ, bass boost, virtualizer, loudness) dipasang ke *global
output session* (`audioSessionId = 0`) memakai API resmi Android
(`android.media.audiofx`). Di kebanyakan ROM stock/AOSP-based, ini membuat
efek berlaku ke SEMUA aplikasi (Spotify, YouTube, game, dst) **tanpa root**
— trik yang sama dipakai aplikasi seperti "Music Volume EQ". Ini murni
legal dan didukung Android, bukan celah/bug.

**Root mode (opsional):**
Jujur soal batasnya:
- Loudness boost dinaikkan batasnya dari +12 dB ke +30 dB. Ini sebenarnya
  bisa dilakukan tanpa root juga (itu kapasitas asli API-nya) — kami
  menguncinya di belakang toggle root semata sebagai **pengaman**, karena
  gain setinggi itu berisiko membuat suara pecah/distorsi.
- Yang BENAR-BENAR butuh root: tombol **"Restart Audio Server"**
  (mem-`kill` proses `audioserver` sistem supaya efek reload bersih kalau
  macet) dan hook boot lebih awal (`RootUtils.installEarlyBootHook`).
- Yang TIDAK dilakukan project ini (dan ini penting untuk ekspektasi):
  membuat driver DSP tingkat kernel/HAL seperti Viper4Android. Itu proyek
  yang jauh lebih besar (custom native audio driver per-chipset), tidak
  realistis dibangun dalam satu sesi seperti ini, dan berisiko membuat
  device bootloop kalau salah implementasi.

Jika beberapa OEM (device tertentu, jarang) memblokir efek pada session 0,
mode root tidak bisa memperbaikinya — itu batasan di level vendor audio
HAL, bukan sesuatu yang bisa di-workaround dari level aplikasi Android biasa.

## Struktur project

```
StarrySound/
├── app/src/main/java/com/fxsoundlite/app/
│   ├── MainActivity.kt        # Layar utama (Beranda + Screensaver)
│   ├── HomeFragment.kt        # Beranda: power, tombol EQ, bass/surround/loudness
│   ├── MusicFragment.kt       # Tab Musik: sumber, daftar, pencarian, kontrol pemutar
│   ├── QuickFragment.kt       # Tab Quick: feed reff ala Reels + tombol aksi
│   ├── QuickAdapter.kt        # Satu halaman reel per lagu
│   ├── QuickFeed.kt           # Sumber lagu Quick: rekomendasi, acak, anti-ulang
│   ├── MusicService.kt        # Pemutar (MediaPlayer + mesin online + MediaSession + notifikasi media)
│   ├── RemoteEngine.kt        # Antarmuka mesin pemutar online (+ jam interpolasi)
│   ├── YouTubeEngine.kt       # Pemutaran YouTube (IFrame Player di WebView tersembunyi)
│   ├── YouTubeApi.kt          # Pencarian YouTube Data API v3 (API key)
│   ├── MusicPrefs.kt          # Simpan API key YouTube dan sumber terakhir
│   ├── Http.kt, ImageLoader.kt # HTTP minimal (tanpa OkHttp) + thumbnail hasil pencarian
│   ├── MusicLibrary.kt        # Baca daftar lagu dari MediaStore
│   ├── Track.kt               # Model satu lagu (lokal / YouTube)
│   ├── EqualizerActivity.kt   # Menu EQ penuh: preset, master gain, slider band
│   ├── SettingsActivity.kt    # Layar Settings: pemilih bahasa + Mode Root + login Google
│   ├── AiRecommender.kt       # Rekomendasi musik AI (Gemini gratis -> YouTube)
│   ├── DriveSync.kt           # Login Google + cadangan/sinkron ke Drive (appDataFolder)
│   ├── LocaleHelper.kt        # Simpan/terapkan pilihan bahasa in-app
│   ├── AudioEffectManager.kt  # Inti kontrol EQ/bass/virtualizer/loudness
│   ├── Presets.kt             # Definisi preset
│   ├── EffectService.kt       # Foreground service (efek tetap hidup)
│   ├── BootReceiver.kt        # Auto-reapply setelah reboot
│   └── RootUtils.kt           # Deteksi root, shell su, restart audioserver
├── app/src/main/res/          # Layout, strings (id & en), tema gelap
└── build.gradle, settings.gradle, gradle.properties
```

## Menghemat ukuran & RAM lebih lanjut (opsional)

- `minifyEnabled true` + `shrinkResources true` sudah aktif di build rilis.
- Tidak ada dependency berat (tidak pakai ExoPlayer, tidak pakai library
  root pihak ketiga) — hanya AndroidX inti + Material.
- Kalau mau lebih kecil lagi: hapus Material Components, ganti Switch/Spinner
  dengan widget native `android.widget.*` (sudah dipakai untuk sebagian).

## Update otomatis

- **Build mingguan:** `.github/workflows/build.yml` berjalan tiap push ke `main`, manual, dan
  **tiap Senin**. Setiap build memakai NewPipeExtractor rilis terbaru, ditandatangani dengan
  `app/starrysound.jks` (kunci tetap, supaya APK baru bisa dipasang menimpa yang lama), lalu
  dipublikasikan sebagai GitHub Release `build-<N>` berisi satu file `.apk`.
- **Di aplikasi:** saat dibuka (maksimal sekali per 12 jam) aplikasi memeriksa release terbaru
  repo ini. Kalau ada yang lebih baru muncul dialog **Pasang / Nanti**; APK diunduh lalu
  dipasang lewat installer sistem (izinkan "pasang aplikasi dari sumber ini" saat diminta).
- Perlu repo GitHub yang menjalankan workflow tersebut (nama repo diisi otomatis lewat
  `-PupdateRepo`). Build lokal tidak punya pengecekan update.
- Keystore dan passwordnya ada di repo: cukup untuk pemakaian pribadi. Kalau repo publik dan
  kamu peduli keamanan, pindahkan ke GitHub Secrets.

## Pemutaran YouTube

Urutan: **NewPipeExtractor** (stream audio langsung) -> **pemutar embed resmi** -> **halaman youtube.com asli di WebView tersembunyi**.
(`android-youtube-player`) sebagai cadangan. Pencarian tidak butuh API key (key opsional).
