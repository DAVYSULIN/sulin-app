# Reglas de ProGuard/R8 para SULIN | Centro de Comando
# Por ahora minifyEnabled está en "false" (ver app/build.gradle), por lo que
# estas reglas no se aplican todavía. Quedan listas para cuando decidan
# activar la ofuscación/reducción de código en el build de release.

# Mantener métodos anotados con @JavascriptInterface si en el futuro se agrega
# un puente WebView -> JavaScript (window.Android.metodo() desde la web).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView / WebChromeClient / WebViewClient no necesitan reglas especiales,
# pero se mantiene el paquete de la app por claridad en stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
