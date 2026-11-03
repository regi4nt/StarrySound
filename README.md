# StarrySound

Pemutar musik, equalizer, dan penemu lagu untuk Android. Memutar lagu dari perangkat, Google Drive,
YouTube, radio internet, dan siaran TV; menerapkan equalizer dan efek suara ke audio; menyimpan
playlist; merekomendasikan lagu dari seleramu; dan punya alat tambahan seperti alarm bangun dengan
lagu, timer tidur, dan screensaver.

- Android 8.0 ke atas (API 26), target API 34.
- Bahasa: Indonesia dan English (pemilih bahasa ada di Settings, tidak perlu ganti bahasa sistem).
- Tanpa akun wajib, tanpa iklan, tanpa server milik aplikasi. Semua kunci API yang kamu isi hanya
  tersimpan di perangkat.
- Nama internal kode tetap `com.fxsoundlite.app`, sedangkan `applicationId` aplikasi adalah
  `com.starrysound.app`.

## Isi

1. [Navigasi](#navigasi)
2. [Equalizer dan efek suara](#equalizer-dan-efek-suara)
3. [Pemutar](#pemutar)
4. [Sumber musik dan video](#sumber-musik-dan-video)
5. [Playlist](#playlist)
6. [Home, Quick, dan rekomendasi](#home-quick-dan-rekomendasi)
7. [Alat tambahan](#alat-tambahan)
8. [Akun, cadangan, dan privasi](#akun-cadangan-dan-privasi)
9. [Settings](#settings)
10. [Widget, shortcut, dan integrasi sistem](#widget-shortcut-dan-integrasi-sistem)
11. [Izin](#izin)
12. [Setup kunci dan layanan](#setup-kunci-dan-layanan)
13. [Batasan](#batasan)
14. [Build](#build)
15. [Update otomatis](#update-otomatis)
16. [Struktur project](#struktur-project)

## Navigasi

Bilah bawah punya empat tab: **Home**, **Search**, **Playlist**, dan **Quick**. Halaman
**Screensaver** dibuka dengan menggeser dari Home atau lewat ikonnya di Home (atau widget).
Mini player muncul di atas bilah bawah (kecuali di tab Quick) dan membuka pemutar layar penuh
kalau diketuk. Pemutaran tetap jalan saat aplikasi ditutup.

## Equalizer dan efek suara

Dibuka dari tile **Equalizer** di Home.

- **Equalizer** multi-band. Jumlah band mengikuti chip perangkat (biasanya 5); kurva preset
  digambar ulang ke band milik perangkat dengan interpolasi frekuensi logaritmik.
- **16 preset**: Default, Auto, General, Music, Voice, Volume Boost, Gaming, Classic Processing,
  Light Processing, Bass Boost, Streaming Video, Movies, TV, Transcription, Dolby Style, dan DTS Style.
  Sebagian besar diporting dari preset desktop FXSound. **Auto** memilih pengaturan sesuai
  kemampuan perangkat.
- **Slider efek**: Bass Boost, Surround (Virtualizer), Loudness, Ambience (reverb), dan
  Volume Leveling (kompresor ringan dengan limiter pengaman, butuh Android 9+).
- **Master Gain**: geser seluruh kurva naik atau turun tanpa merusak bentuk kurva.
- **Preset otomatis per output**: kartu di layar Equalizer (mati secara bawaan). Speaker,
  headphone berkabel/USB, dan Bluetooth masing-masing punya preset sendiri yang diterapkan saat
  output berganti. Logikanya ada di `OutputPresets.kt`, deteksi di `EffectService`.
- **Notifikasi preset** dengan tombol ganti preset dan **Matikan**, serta efek diterapkan ulang
  setelah reboot (`BootReceiver`).

### Ke mana efek dipasang

- Saat lagu lokal atau stream YouTube diputar, rantai efek dipasang ke sesi audio pemutar
  StarrySound sendiri.
- Saat jeda, berhenti, atau audio dari aplikasi lain, rantai efek kembali ke mix global
  (session 0), **kecuali Mode aman volume aktif** (lihat di bawah).
- Spotify dan aplikasi lain yang mengumumkan sesi audionya lewat broadcast standar Android
  (`OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION`) juga bisa dikenai efek (`SpotifyEffects.kt`).

### Mode aman volume

Saklar di Settings, **aktif otomatis di Android 13 ke atas**. Di Android 13 efek global (session 0)
sudah deprecated, dan di ROM seperti MIUI/HyperOS bisa bentrok dengan efek audio bawaan sehingga
tombol volume terasa tidak berfungsi saat EQ menyala. Pada mode ini:

- tidak ada efek di mix global; efek hanya hidup di sesi pemutar StarrySound dan dilepas saat tidak
  ada lagu diputar (audio aplikasi lain tidak kena EQ);
- Ambience dimatikan (efek reverb selalu menempel di session 0);
- kurva EQ diberi headroom otomatis (preamp sebesar boost tertingginya) agar tidak pecah. Nilai
  slider yang tersimpan tidak berubah;
- **Loudness tidak dipakai** (slider diredupkan) begitu juga limiter yang dulu ikut menyala bersamanya.
  Di HP seperti Redmi Note 11 (Android 13, MIUI) keduanya diduga membuat tombol volume tidak
  berfungsi saat EQ menyala. Limiter hanya menyala untuk Volume Leveling. Nilai Loudness tetap
  tersimpan dan kembali aktif bila saklar dimatikan.

Matikan saklar untuk memasang EQ ke seluruh ponsel seperti sebelumnya.

### Root mode (opsional)

Tanpa root semua efek memakai API resmi `android.media.audiofx`. Root mode hanya menambah:

- batas Loudness naik dari +12 dB ke +30 dB (dikunci di belakang saklar sebagai pengaman karena
  rawan distorsi, bukan karena butuh root);
- tombol **Restart audio server** dan **Periksa efek** (membaca dump audio flinger);
- hook boot lebih awal dan pengecekan kesehatan efek tiap 30 detik.

Aplikasi ini **bukan** driver DSP tingkat kernel seperti Viper4Android. Kalau ROM memblokir efek
di level vendor HAL, root mode tidak memperbaikinya.

## Pemutar

Pemutaran berjalan di `MusicService` (foreground service), jadi tetap berjalan saat aplikasi
ditutup, dengan notifikasi media, kontrol layar kunci dan headset, audio focus (jeda saat telepon),
dan jeda otomatis saat headphone dicabut. Lagu lokal dan stream memakai `MediaPlayer` bawaan
Android (tanpa ExoPlayer).

- Antrean, **acak**, **ulangi** (mati / semua / satu lagu), seek, dan **kecepatan putar**.
- **Smart shuffle**: menyisipkan lagu yang cocok dengan tema antrean. Dengan kunci AI dipilih AI;
  tanpa kunci diambil dari pencarian YouTube artis yang sama.
- **Lirik** dari LRCLIB (bergulir mengikuti lagu bila ada versi bertanda waktu); untuk video YouTube
  tanpa lirik dipakai subtitle video. Ada romanisasi (offline) dan terjemahan ke Indonesia atau
  English.
- **Lewati bagian non-musik** pada lagu YouTube (intro, outro, sponsor) lewat data SponsorBlock.
  Mati secara bawaan karena menghubungi server pihak ketiga.
- Warna aksen mengikuti sampul lagu, bentuk sampul bisa diatur, dan sampul bisa diganti foto sendiri.
- **Unduh** lagu YouTube ke `Music/StarrySound`.
- **Bagikan** lagu sebagai kartu gambar bergaya Spotify berisi sampul, judul, dan link; di lembar
  Share ada tujuan tambahan **Salin link** yang menyalin link saja.
- **Android Auto / Bluetooth**: `MusicService` adalah `MediaBrowserService`, jadi pustaka bisa
  dijelajahi dari layar mobil.
- **Mode video**: lagu YouTube bisa dialihkan ke pemutar video, dan file video di perangkat atau
  Drive diputar di pemutar video yang tampilannya sama dengan pemutar musik (termasuk
  picture-in-picture). Menutup video melanjutkan audionya di mini player.
- **Hemat data**: saklar manual atau otomatis di jaringan berbayar; menyembunyikan sampul dan
  menghitung data yang terpakai.

## Sumber musik dan video

Tab **Search** punya tab sumber. Tab Perangkat selalu ada; yang lain bisa ditampilkan atau
disembunyikan di **Settings > Online music > Search tabs**. YouTube, TV, dan Radio aktif secara
bawaan; Web dan Spotify tersembunyi sampai dinyalakan.

| Sumber | Isi | Catatan |
|---|---|---|
| **Perangkat** | Semua lagu (atau video, di mode video) dari penyimpanan | Dibaca dari MediaStore tanpa menyalin file. Dialog **Storage** menampilkan penyimpanan internal, SD card, dan OTG; folder bisa ditambah lewat pemilih file sistem (cara membaca OTG yang tidak diindeks sistem). Ada filter dan urutan. |
| **Drive** | File audio dan video di Google Drive-mu | Perlu masuk Google dan izin baca saja. File diunduh ke cache dulu (maks 300 MB, yang paling lama tidak dipakai dihapus). |
| **YouTube** | Pencarian dan pemutaran | Pencarian memakai NewPipeExtractor tanpa kunci; kunci YouTube Data API v3 opsional. Bisa menempel link YouTube di kotak cari. |
| **Radio** | Stasiun radio internet | radio-browser, SomaFM, Icecast, Radio Garden, Shoutcast, Radio Paradise, plus koleksi stasiun pilihan di aplikasi. Status stasiun (dapat dijangkau atau tidak) diperiksa di latar belakang. Mendukung stream `.pls`, `.m3u`, dan HLS. |
| **TV** | Siaran TV langsung (mode video) | Playlist gratis IPTV-org per negara, plus link M3U/M3U8 milikmu sendiri. |
| **Web** | Di mode video: browser kecil untuk menonton video di situs. Di mode musik: Internet Archive, Audius, Jamendo, ccMixter | Satu saklar mengatur dua tab ini. Jamendo butuh Client ID gratis. Situs favorit disimpan dengan bintang. |
| **Spotify** | Remote untuk aplikasi Spotify, plus impor playlist | Audio Spotify hanya bisa diputar di aplikasi Spotify; StarrySound mengendalikannya. Lihat bagian Spotify di bawah. |

Urutan pemutaran YouTube: **NewPipeExtractor** (stream audio langsung) -> **pemutar embed resmi**
(IFrame di WebView tersembunyi) -> **halaman youtube.com asli di WebView tersembunyi**. Pemutaran
YouTube di luar aplikasi resminya melanggar ketentuan layanan YouTube; pakai untuk keperluan pribadi
dan pahami risikonya.

### Spotify

- **Kendali**: play/jeda/berikutnya/sebelumnya lewat sesi media aplikasi Spotify (butuh akses
  notifikasi), dan "putar playlist ini" lewat Web API (butuh Spotify Premium dan Spotify sudah
  pernah dibuka sekali agar punya perangkat aktif). Equalizer StarrySound bisa dikenakan ke audio
  Spotify.
- **Impor playlist**: dari akun (login OAuth PKCE; tiap pengguna memakai Client ID miliknya sendiri,
  dan Spotify hanya mengembalikan lagu dari playlist **milikmu sendiri**), atau dari **link
  playlist publik tanpa akun** (hanya sekitar 100 lagu pertama). Setiap lagu dicocokkan ke versi
  YouTube yang bisa diputar (judul + artis, durasi terdekat); yang tidak ketemu dilewati dan
  dihitung. Hasil impor masuk ke folder **Spotify** di halaman Playlist.

## Playlist

Tab **Playlist** berisi:

- **Liked songs** (selalu paling atas) dan playlist buatan sendiri, dengan sampul sendiri atau sampul
  lagu pertama.
- **Folder buatan sendiri** untuk mengelompokkan playlist (satu level, tidak bersarang). Playlist
  dipindah lewat tekan-lama atau menu titik tiga; menghapus folder tidak menghapus playlist.
- **Folder sumber**: **Perangkat**, **Drive**, **YouTube**, dan **Spotify**. Playlist dari
  masing-masing sumber tidak langsung muncul di daftar utama, melainkan di dalam foldernya.
  Folder hanya muncul bila sumbernya tersedia (folder lagu di perangkat, sudah masuk Google, atau
  ada playlist hasil impor Spotify). Pencarian dan filter jenis menampilkan semuanya tanpa
  pengelompokan; playlist yang di-pin atau terakhir diputar tetap muncul di daftar utama.
  - **Perangkat**: satu playlist per folder yang berisi lagu (label Perangkat / SD card / OTG).
  - **Drive**: satu playlist per folder Drive yang berisi audio.
  - **YouTube**: playlist akun YouTube yang sedang masuk (hanya baca).
  - **Spotify**: playlist hasil impor.
- **Pin** hingga 4 playlist agar tetap di atas.
- **Impor dan ekspor**: file M3U/M3U8, link playlist YouTube / YouTube Music (tanpa kunci), link
  berbagi `starrysound://playlist?d=...` (lagu YouTube ikut di dalam link), dan cadangan JSON seluruh
  playlist. Berbagi sebagai link hanya menyertakan lagu YouTube; lagu lokal tidak ikut.
- **Buat playlist dengan AI** dari permintaan teks, atau dari seleramu tanpa teks.
- **Konversi** playlist lokal ke playlist akun YouTube dan sebaliknya.
- Di dalam playlist: urutkan, filter, pilih banyak lagu (hapus / pindah), dan tekan-lama untuk menu
  lagu.

## Home, Quick, dan rekomendasi

**Home** menampilkan sapaan sesuai waktu, kartu statistik, empat tile (Alarm bangun, Equalizer, Sleep
sounds, Deteksi lagu) plus tile unduh dari link, lalu rak horizontal: Terakhir diputar, Lagu teratas
dan Rilis baru (tangga lagu dari RSS publik Apple, sesuai negara perangkat), playlist
Rekomendasi dan Populer, serta radio. Rak **Pilihan AI untukmu** muncul bila kunci AI diisi.

**Quick** adalah feed vertikal ala Reels: satu halaman satu lagu, dan yang diputar hanya
**reff-nya** (klip sekitar 25 detik; posisi reff adalah perkiraan karena YouTube tidak menyediakan
penanda reff). Klip habis -> otomatis turun ke lagu berikutnya. Aksi di sisi kanan: putar penuh,
tambah ke playlist, tambah ke antrean, unduh, bagikan, serta menu lirik dan volume. Rekomendasi
berasal dari "related videos" YouTube, artis favoritmu, dan penemuan acak, tanpa akun YouTube.
Pratinjau memakai mesin sendiri sehingga antrean di `MusicService` tidak terganggu.

**Mempelajari seleramu**: artis teratas, lagu yang disukai, riwayat putar, sinyal dari aplikasi lain
(YouTube / YouTube Music / Spotify yang terdeteksi lewat akses notifikasi), dan Last.fm bila
terhubung. Semuanya tetap di perangkat.

**Rekomendasi dan playlist AI** memakai layanan pilihanmu sendiri: **Gemini**, **OpenAI**, atau
**Anthropic**, masing-masing dengan kunci API sendiri. Bila beberapa kunci diisi, permintaan dibagi
rata dan layanan yang kena batas diistirahatkan sementara. Yang dikirim hanya nama artis dan judul
lagu dari like dan riwayatmu, plus teks yang kamu ketik untuk playlist AI, dan hanya ke layanan yang
kamu pilih.

**Statistik dan Wrapped**: waktu dengar, jumlah putar (dihitung setelah lagu didengar beberapa
detik), artis dan lagu teratas, jam favorit, dan sumber. **Wrapped** adalah rangkuman bulanan atau
tahunan, bisa dibagikan sebagai kartu gambar. Bulan hanya tercatat sejak versi yang menambahkannya.

## Alat tambahan

- **Deteksi lagu** (tile di Home), tiga mode:
  - **Nyanyi**: lirik yang kamu nyanyikan dikenali pengenal suara sistem lalu dicari di YouTube.
    Teks bisa diedit sebelum dicari ulang.
  - **Senandung**: klip sekitar 12 detik dicocokkan lewat melodi memakai **ACRCloud** (butuh host,
    access key, dan access secret milikmu sendiri).
  - **Lagu di sekitar**: merekam lagu yang sedang berbunyi lalu mengenalinya lewat endpoint publik
    Shazam, tanpa kunci. Tidak resmi, jadi bisa berubah atau diblokir kapan saja; tidak mengenali
    senandung.
- **Unduh dari link**: tempel link lalu pilih apa yang disimpan (lagu atau kualitas video). Mendukung
  YouTube, SoundCloud, Bandcamp, PeerTube, dan sejenisnya lewat NewPipeExtractor; TikTok, Instagram,
  dan Facebook secara best effort; link file media langsung; dan halaman web lain yang menyematkan
  audio/video. Link yang dibagikan dari aplikasi lain (menu Share -> StarrySound) juga diterima.
- **Sleep sounds**: timer tidur yang meredam volume pelan-pelan lalu menjeda, dan memilih musik dari
  perangkat, Drive, atau YouTube. Berjalan sebagai service sendiri sehingga tetap jalan saat layar mati.
- **Alarm bangun dengan lagu**: waktu, hari ulang, lagu pembangun, dan lama tunda. Memakai
  `AlarmManager.setAlarmClock` (tepat waktu, tembus Doze) dan tampil di atas layar kunci. Bila lagu
  tidak bisa mulai (offline, file terhapus), suara alarm sistem mengambil alih setelah beberapa detik.
- **Screensaver**: jam (digital atau analog) dan tanggal di atas video atau foto pilihanmu, atau
  latar animasi bawaan tanpa file; ada sapaan, judul lagu yang sedang diputar, indikator baterai,
  dan efek sentuh. **Screensaver otomatis saat mengisi daya** (mati secara bawaan) membukanya di atas
  layar kunci tanpa membuka kunci HP; perlu izin notifikasi dan, di Android 14+, notifikasi layar
  penuh.
- **Listen together**: tuan rumah dan tamu memutar lagu YouTube yang sama di posisi yang sama,
  dengan tampilan ruang ala Discord (anggota, kartu lagu yang sedang diputar, antrean, dan usulan
  lagu dari tamu). Audio tidak dikirim; tiap HP memutar sendiri dan hanya status pemutaran yang
  dikirim lewat Firebase Realtime Database milikmu (REST, tanpa SDK). Undangan lewat link
  `starrysound://room/...`.
- **Layar penuh otomatis** (Settings > General): menyembunyikan bilah status dan navigasi di semua
  halaman, termasuk area notch.
- **Kunci aplikasi**: sidik jari, wajah, atau kunci layar (PIN/pola/sandi) saat aplikasi dibuka dan
  setelah keluar lebih lama dari jeda pilihanmu. Musik dan kontrol notifikasi tetap jalan saat
  terkunci. Bila HP tidak punya kunci layar lagi, aplikasi tidak dikunci.
- **Buka dengan**: mengetuk file audio atau video di pengelola file menawarkan StarrySound.

## Akun, cadangan, dan privasi

Di **Settings > Google account** ada **Masuk dengan Google**. Setelah masuk:

- playlist, lagu favorit, riwayat YouTube, dan statistik dengar dicadangkan ke folder aplikasi
  tersembunyi di Google Drive-mu (`appDataFolder`, scope `drive.appdata`). File Drive lain tidak
  dibaca oleh cadangan ini;
- kunci API (YouTube, ACRCloud, AI, Last.fm, Spotify) sengaja **tidak** ikut diunggah;
- **lagu perangkat tidak ikut dicadangkan** secara bawaan; saklar **Cadangkan lagu perangkat**
  menyertakannya (dicocokkan lewat judul + artis di perangkat lain; yang tidak ada di perangkat
  itu ditandai hilang tetapi tetap di playlist);
- **Impor dari Drive** (satu arah), **Sinkron otomatis** (mati secara bawaan; mengunggah sekitar 20
  detik setelah perubahan dan menarik data terbaru saat aplikasi dibuka), dan **Sinkronkan sekarang**.
  Bila dua perangkat sama-sama berubah, datanya **digabung**, bukan ditimpa; penghapusan dicatat agar
  tidak hidup lagi;
- **Keluar** hanya memutus akun di aplikasi; data lokal dan cadangan di Drive tetap ada.

Izin tambahan diminta saat fitur terkait dipakai pertama kali: baca Drive (tab Drive), dan baca
YouTube (playlist akun di halaman Playlist). Statistik, riwayat, dan profil selera hanya disimpan di
perangkat. **Last.fm** (opsional) mencatat lagu yang kamu dengar dan antreannya bila offline.

## Settings

Kartu kategori di halaman utama Settings, lalu satu halaman per kategori:

- **General**: mode tema (terang/gelap/sistem), warna aksen, bahasa, kunci aplikasi, layar penuh
  otomatis, tetap memutar.
- **Player**: foto sampul sendiri dan bentuk sampul.
- **Screensaver**: latar, gaya jam, sapaan, judul lagu, efek sentuh, screensaver saat mengisi daya.
- **Online music**: kunci YouTube, AI (Gemini / OpenAI / Anthropic), tab pencarian, kualitas audio,
  hemat data, Last.fm, deteksi lagu (ACRCloud), kunci Jamendo, Spotify, dan pelajari seleramu.
- **Advanced**: preset otomatis per output, notifikasi preset, Mode aman volume, Root mode, Storage
  (bersihkan cache), dan About & help.
- Di atas kartu kategori: **Google account** (masuk, cadangan, dan sinkron). Pemeriksaan pembaruan ada di
  About & help.

## Widget, shortcut, dan integrasi sistem

- **Widget**: (1) **Equalizer** satu sel tinggi: tombol nyala/mati, preset aktif (ketuk untuk ganti),
  dan tombol sebelumnya/berikutnya di ukuran lebar; (2) **Pencarian + Deteksi lagu**; (3)
  **Screensaver** 1x1.
- **Shortcut** ikon aplikasi: pencarian musik, playlist, Quick, dan Screensaver.
- **Link yang dibuka aplikasi**: `starrysound://playlist` (playlist yang dibagikan),
  `starrysound://room` (undangan Listen together), dan `starrysound://spotify-callback` (login Spotify).
- **Menu Share sistem**: teks atau link dari aplikasi lain bisa dikirim ke fitur unduh dari link.
- **Android Auto / Bluetooth**: aplikasi media (lihat bagian Pemutar).

## Izin

| Izin | Dipakai untuk |
|---|---|
| Internet, status jaringan | Semua fitur online |
| Baca audio / video (`READ_MEDIA_*`, atau `READ_EXTERNAL_STORAGE` di Android 12 ke bawah) | Daftar lagu dan video di perangkat |
| Mikrofon (`RECORD_AUDIO`) | Deteksi lagu (nyanyi, senandung, lagu di sekitar) |
| Notifikasi, notifikasi layar penuh | Notifikasi media, alarm, screensaver saat mengisi daya |
| Foreground service (media playback, special use) | Pemutar dan efek tetap hidup |
| Wake lock, terima boot | Pemutaran di latar, efek dan alarm dipasang ulang setelah reboot |
| Ubah pengaturan audio | Efek audio dan fade timer tidur |
| Biometrik | Kunci aplikasi |
| Pasang paket (`REQUEST_INSTALL_PACKAGES`) | Pembaruan otomatis dari GitHub Releases |
| Akses notifikasi (diberikan manual) | Membaca judul lagu dari aplikasi lain, mengendalikan Spotify |

## Setup kunci dan layanan

Semuanya opsional kecuali login Google; tiap fitur menampilkan tombol yang mengarah ke langkah yang
masih kurang.

**Login Google dan Drive** (wajib bila ingin cadangan, tab Drive, atau playlist akun YouTube). Setup
sekali di https://console.cloud.google.com :

1. Buat project, aktifkan **Google Drive API** (dan **YouTube Data API v3** untuk playlist akun).
2. **OAuth consent screen**: isi nama dan email, tambahkan scope `drive.appdata`, `drive.readonly`
   (tab Drive), dan scope baca YouTube. Selama status *Testing*, tambahkan emailmu di **Test users**
   (`drive.readonly` termasuk scope restricted: publikasi ke umum perlu verifikasi Google).
3. **Credentials > OAuth client ID > Android**: package `com.starrysound.app`, SHA-1
   `71:64:90:20:76:A9:E1:F8:0F:47:60:D7:A2:53:9A:B9:3D:6A:17:29` (kunci `app/starrysound.jks` yang
   dipakai semua build, lokal maupun GitHub Actions).
4. Tidak ada client ID atau secret yang ditempel di kode.

Bila masuk gagal, aplikasi menampilkan kode error, nama paket, dan SHA-1 APK yang sedang berjalan
(tombol *Salin SHA-1*). Nilainya harus sama persis dengan client Android di Cloud Console. Kode 10
atau 12500 berarti client tidak cocok; perubahan di Console kadang butuh beberapa menit.

**Kunci lain**

| Fitur | Kunci | Dari mana |
|---|---|---|
| Pencarian YouTube lewat API resmi (opsional; tanpa kunci dipakai NewPipeExtractor) | YouTube Data API v3 key | Google Cloud Console. Satu pencarian memakai 101 unit dari kuota gratis 10.000/hari. |
| Rekomendasi dan playlist AI | Kunci Gemini / OpenAI / Anthropic | aistudio.google.com/apikey, platform.openai.com/api-keys, console.anthropic.com/settings/keys. Pemakaian OpenAI dan Anthropic ditagihkan ke akunmu; di paket gratis Gemini, Google boleh memakai data untuk meningkatkan produknya. Kolom model boleh dikosongkan. |
| Deteksi senandung | Host, access key, access secret ACRCloud (bucket Humming aktif) | console.acrcloud.com |
| Jamendo (tab Web mode musik) | Client ID gratis | developer.jamendo.com |
| Last.fm | API key + shared secret | last.fm/api/account/create; login disetujui di situs Last.fm, password tidak pernah diketik di aplikasi |
| Spotify (login akun) | Client ID aplikasi Spotify milikmu | developer.spotify.com/dashboard; tambahkan `starrysound://spotify-callback` sebagai redirect URI. Spotify mewajibkan Premium untuk aplikasi mode pengembangan dan membatasi 5 pengguna per Client ID. Impor dari link publik tidak butuh ini. |
| Listen together | URL Firebase Realtime Database milikmu | Buat Realtime Database, izinkan baca/tulis pada `rooms`, lalu tempel URL-nya di layar Listen together saat diminta |

## Batasan

- YouTube, Shazam, SponsorBlock, tikwm.com, dan pembaca halaman Spotify/Instagram/Facebook bukan
  jalur resmi; mereka bisa berubah atau memblokir kapan saja. Karena itu build mingguan memakai
  NewPipeExtractor terbaru.
- Posisi reff di Quick adalah perkiraan.
- Lagu Spotify hanya bisa diputar di aplikasi Spotify; StarrySound menjadi remote dan mencocokkan
  impor ke versi YouTube (bisa meleset).
- Lagu perangkat tidak punya link, jadi tidak ikut di link berbagi playlist dan tidak punya "Salin
  link".
- Efek audio bergantung pada ROM. Di Android 13+ (khususnya MIUI/HyperOS) gunakan Mode aman volume;
  tanpa itu sebagian perangkat mengabaikan atau bentrok dengan efek global.
- Tidak ada pengelolaan hak cipta atau DRM; pakai sesuai hukum dan ketentuan tiap layanan.

## Build

Project ini source Android (Gradle, Kotlin, ViewBinding), compileSdk 34, minSdk 26, Java 17.

**GitHub Actions** (`.github/workflows/build.yml`): build berjalan tiap push ke `main`, manual, dan
tiap Senin. Langkahnya memakai NewPipeExtractor rilis terbaru, membangun `assembleRelease`, lalu
mengunggah satu APK ke artifact dan GitHub Release `build-<N>`.

1. Push seluruh isi folder ke repo GitHub.
2. Buka tab **Actions** dan jalankan **Build APK** (atau tunggu otomatis setelah push).
3. Ambil APK dari artifact `StarrySound` atau dari halaman **Releases**.

Semua build (debug maupun release) ditandatangani dengan satu kunci tetap `app/starrysound.jks`
(keystore dan password ada di repo; cukup untuk pemakaian pribadi, pindahkan ke GitHub Secrets bila
repo publik dan kamu peduli keamanan). Karena kuncinya tetap, APK baru bisa dipasang menimpa yang lama.
Build release memakai R8 (`minifyEnabled`, `shrinkResources`) dan hanya menyertakan bahasa en/in/id
serta ABI `arm64-v8a` dan `armeabi-v7a`.

**Lokal**: buka folder di Android Studio, tunggu Gradle sync, lalu Run di HP (USB debugging) atau
emulator; build rilis: `./gradlew :app:assembleRelease`. Folder `gradle/wrapper` hanya berisi
`gradle-wrapper.properties` (Gradle 8.4), tanpa `gradle-wrapper.jar`; Android Studio akan membuatnya
ulang saat pertama dibuka, sedangkan workflow memakai Gradle yang dipasang runner.

`applicationId` adalah `com.starrysound.app`, jadi aplikasi ini terpasang terpisah dari versi lama
(StarrySound Lite) dan tidak menimpanya. Jika keduanya terpasang, matikan efek di salah satunya agar
tidak memasang equalizer ke output yang sama.

## Update otomatis

Saat dibuka (maksimal sekali per 12 jam), aplikasi memeriksa release `build-<N>` terbaru di repo
GitHub yang membangunnya (nama repo diisi otomatis lewat `-PupdateRepo`). Bila ada yang lebih baru
muncul dialog **Pasang / Nanti**; APK diunduh lalu dipasang lewat installer sistem (izinkan
"pasang aplikasi dari sumber ini" saat diminta). Build lokal tanpa `updateRepo` tidak punya
pengecekan update.

## Struktur project

```
StarrySound/
├── app/src/main/java/com/fxsoundlite/app/
│   ├── Halaman utama
│   │   MainActivity, HomeFragment, MusicFragment (tab Search), PlaylistFragment,
│   │   QuickFragment, ScreensaverFragment, SettingsActivity, EqualizerActivity
│   ├── Pemutar
│   │   MusicService (MediaPlayer + mesin online + MediaSession + MediaBrowserService),
│   │   NowPlayingActivity, VideoPlayerActivity, WebVideoActivity, RemoteEngine,
│   │   YouTubeEngine / YouTubeStreamEngine / YouTubeEmbedEngine / YouTubeWebEngine,
│   │   SpotifyEngine, SmartShuffle, SegmentSkipper, LyricsApi, LyricsText
│   ├── Efek audio
│   │   AudioEffectManager, EffectService, Presets, OutputPresets, SpotifyEffects,
│   │   BootReceiver, RootUtils
│   ├── Sumber musik
│   │   MusicLibrary, FolderLibrary, Storages, DriveMusic, YouTubeApi, YouTubeAccount,
│   │   RadioApi, RadioSources, RadioCollection, TvChannels, OnlineApi, ArchiveApi,
│   │   SpotifyApi, SpotifyAuth, SpotifyEmbed, SpotifyImport, SpotifyRemote
│   ├── Playlist
│   │   PlaylistStore, PlaylistFolders (+ SpotifyLists), PlaylistPins, PlaylistIo,
│   │   PlaylistGenerator, PlaylistUi, LocalMatcher
│   ├── Rekomendasi dan statistik
│   │   QuickFeed, HomeFeeds, HomePlaylists, AiRecommender, AiProvider, AiLoad,
│   │   LastFm, StatsStore, TasteSignals, WrappedActivity
│   ├── Alat
│   │   SongDetectActivity, SongRecognizer, ShazamRecognizer, ShazamFingerprint,
│   │   LinkDownloadActivity, LinkDownloader, SongDownloader, BedtimeActivity,
│   │   SleepTimerService, WakeAlarm, WakeAlarmActivity, ListenTogether*,
│   │   ChargingScreensaver*, AppLock*, CopyLink, SongShareCard, WrappedShareCard
│   ├── Akun dan sistem
│   │   DriveSync, UpdateChecker, LocaleHelper, ThemeHelper, DataUsage, CacheCleaner,
│   │   NowPlayingListenerService, ExternalOpenActivity, EqWidgetProvider,
│   │   MusicSearchWidgetProvider, ScreensaverWidgetProvider
│   └── Pendukung: Http, NewPipeDownloader, ImageLoader, NetStatus, CrashLog, dll.
├── app/src/main/res/          # layout, string (en, in, id), tema terang/gelap, widget, shortcut
├── app/starrysound.jks        # kunci penandatanganan tetap
├── .github/workflows/build.yml
└── build.gradle, settings.gradle, gradle.properties
```
