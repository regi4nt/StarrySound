// Tambahkan ke app/build.gradle.kts
// 1) taruh discord_partner_sdk.aar (dari paket Discord Social SDK) di app/libs/
dependencies {
    implementation(files("libs/discord_partner_sdk.aar"))
}
// 2) di android { buildTypes { release { ... } } } tambahkan:
//    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-discord.pro")
// 3) pastikan libs native tidak dikompres/ter-ekstrak ulang secara tidak konsisten:
//    android { packaging { jniLibs { useLegacyPackaging = false } } }
