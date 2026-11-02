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

# veraPDF models, its rule engine and JAXB profiles resolve classes and members through reflection.
-keep class org.verapdf.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class javax.xml.bind.** { *; }
-keep class com.sun.xml.bind.** { *; }
