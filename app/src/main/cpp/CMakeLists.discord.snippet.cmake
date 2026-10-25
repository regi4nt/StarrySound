# Tambahkan setelah add_library(starry_discord ...)
# 16 KB page size (Android 15+); NDK r27+ sudah default.
target_link_options(starry_discord PRIVATE "-Wl,-z,max-page-size=16384")
