# The settings screens are created from their class name (HomeFragment opens them by the names in Home.kt).
-keep class com.metrowin.keyboard.settings.*Fragment { <init>(); }
# Views inflated from XML, and the input method and accessibility services named in the manifest, are kept by the resource rules.
# Stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
