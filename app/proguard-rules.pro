# Conservar el puente JavascriptInterface de Chatiux para notificaciones
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.zonadebusqueda.chatiux.MainActivity$ChatiuxJsBridge { *; }
