Perfetto! 💡 Quindi vuoi seguire l’approccio “Kotlin + Gradle + riga di comando + APK installabile su telefono via ADB”: minimale, snello, senza Android Studio pesante, con iterazioni rapide.

Ti descrivo lo schema completo e i passaggi per partire subito.

🧩 1️⃣ Struttura minima del progetto Kotlin Android

Puoi creare una struttura minimale così:

ble-proxy/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ app/
│  ├─ src/main/AndroidManifest.xml
│  ├─ src/main/java/com/example/bleproxy/
│  │   ├─ MainActivity.kt
│  │   └─ BleService.kt

2️⃣ build.gradle.kts minimale
plugins {
id("com.android.application") version "8.3.0" apply false
kotlin("android") version "1.9.0" apply false
}

tasks.register("clean", Delete::class) {
delete(rootProject.buildDir)
}

App module app/build.gradle.kts
plugins {
id("com.android.application")
kotlin("android")
}

android {
namespace = "com.example.bleproxy"
compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bleproxy"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.7.0")
}

3️⃣ AndroidManifest.xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
package="com.example.bleproxy">

    <uses-permission android:name="android.permission.BLUETOOTH"/>
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>

    <application
        android:label="BLE Proxy"
        android:icon="@mipmap/ic_launcher">
        <activity android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

        <service
            android:name=".BleService"
            android:foregroundServiceType="connectedDevice"
            android:exported="true"/>
    </application>
</manifest>

4️⃣ MainActivity.kt
package com.example.bleproxy

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity: AppCompatActivity() {
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
// Avvia il servizio foreground BLE
val serviceIntent = Intent(this, BleService::class.java)
startForegroundService(serviceIntent)
finish() // chiudi activity, l'app resta attiva in background
}
}

5️⃣ BleService.kt minimale
package com.example.bleproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

class BleService: Service() {

    override fun onCreate() {
        super.onCreate()
        val channelId = "ble_proxy_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "BLE Proxy", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BLE Proxy")
            .setContentText("Servizio BLE attivo")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: qui puoi ricevere Intent dal JS/Tasker e inoltrarli via BLE
        Log.d("BleService", "Servizio avviato")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

🧰 6️⃣ Compilazione e installazione via riga di comando

Apri terminale nella root del progetto (ble-proxy/).

Compila APK debug:

./gradlew assembleDebug


Output: app/build/outputs/apk/debug/app-debug.apk

Installa su telefono reale via ADB:

adb install -r app/build/outputs/apk/debug/app-debug.apk


Avvia l’app sul telefono → servizio foreground BLE attivo → pronto a ricevere comandi via Intent da MacroDroid/JS.

✅ Vantaggi di questo approccio

Leggero: niente Android Studio, niente emulatori.

Iterazioni rapide: modifica Kotlin → ./gradlew assembleDebug → adb install.

Controllo totale: puoi implementare API BLE, bridge Intent, logging.

Compatibile MacroDroid/JS: il servizio foreground può ricevere Intent da MacroDroid e tradurli in azioni BLE.

Se vuoi, posso prepararti una versione pronta con ricezione Intent + esempio BLE connect/send, così puoi partire subito con il proxy BLE senza scrivere altro codice BLE complesso.

Vuoi che faccia questa versione pronta da riga di comando?