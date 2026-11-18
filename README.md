# PDF Baca

PDF reader Android sederhana, gratis, tanpa iklan.

## Fitur
- **Terbaru**: daftar PDF yang terakhir dibuka (tekan lama untuk menghapus dari daftar)
- **Folder**: jelajah folder HP / kartu SD, hanya menampilkan folder dan file PDF
- **Semua PDF**: memindai seluruh PDF di HP, diurutkan dari yang terbaru
- Menu **Buka file…**: pemilih file sistem (Google Drive, Downloads, dll.)
- Viewer: zoom cubit dua jari, ketuk dua kali untuk zoom, tombol − / +, ketuk angka % untuk kembali ke 100%,
  putar 90°, ketuk nomor halaman untuk lompat ke halaman tertentu
- Ingat halaman terakhir dan rotasi tiap file
- Bisa dipilih saat membuka PDF dari WhatsApp / aplikasi lain

## Build APK lewat GitHub
1. Buat repo baru di GitHub (misal `pdf-baca`, private).
2. Upload semua isi folder ini (termasuk folder `.github`) ke repo.
3. Buka tab **Actions** → workflow **Build APK** berjalan otomatis (±5 menit).
4. Setelah selesai, buka **Releases** → unduh `PdfBaca.apk` → pasang di HP.

Pertama kali dibuka, ketuk **Izinkan** lalu aktifkan "Izinkan akses untuk mengelola semua file"
supaya tab Folder dan Semua PDF bisa membaca file.

Catatan: `app/pdfbaca.keystore` adalah kunci tanda tangan APK; jangan dihapus supaya update bisa
langsung menimpa versi lama. Simpan repo dalam mode private.
