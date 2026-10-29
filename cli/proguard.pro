-dontobfuscate
-dontoptimize
-dontnote
-dontwarn **
-ignorewarnings
-keepattributes *

-keep class stirling.software.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class org.apache.logging.log4j.** { *; }
-keep class org.apache.commons.logging.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.apache.poi.poifs.crypt.** { *; }
-keep class org.apache.poi.schemas.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class com.microsoft.schemas.** { *; }
-keep class org.etsi.** { *; }
-keep class org.w3.** { *; }
