Distribution file untuk Gradle wrapper — disalin otomatis oleh gradle wrapper task,
atau jalankan `gradle wrapper` sekali di PC. GitHub Actions workflow sudah
menjalankan `./gradlew`, jadi wrapper JAR perlu ada.

Cara termudah: di PC dengan Android Studio, buka proyek → Android Studio
mengenerate wrapper otomatis. Atau: `gradle wrapper --gradle-version 8.2`.
