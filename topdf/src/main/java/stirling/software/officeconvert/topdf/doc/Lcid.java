package stirling.software.officeconvert.topdf.doc;

import java.util.Map;

final class Lcid {

    private static final Map<Integer, String> TAGS = Map.ofEntries(Map.entry(0x0409, "en-US"),
            Map.entry(0x0809, "en-GB"), Map.entry(0x0c09, "en-AU"), Map.entry(0x1009, "en-CA"),
            Map.entry(0x1409, "en-NZ"), Map.entry(0x1809, "en-IE"), Map.entry(0x0407, "de-DE"),
            Map.entry(0x0807, "de-CH"), Map.entry(0x0c07, "de-AT"), Map.entry(0x040c, "fr-FR"),
            Map.entry(0x080c, "fr-BE"), Map.entry(0x0c0c, "fr-CA"), Map.entry(0x100c, "fr-CH"),
            Map.entry(0x0410, "it-IT"), Map.entry(0x0c0a, "es-ES"), Map.entry(0x040a, "es-ES"),
            Map.entry(0x080a, "es-MX"), Map.entry(0x0416, "pt-BR"), Map.entry(0x0816, "pt-PT"),
            Map.entry(0x0413, "nl-NL"), Map.entry(0x0813, "nl-BE"), Map.entry(0x0406, "da-DK"),
            Map.entry(0x041d, "sv-SE"), Map.entry(0x0414, "nb-NO"), Map.entry(0x0814, "nn-NO"),
            Map.entry(0x040b, "fi-FI"), Map.entry(0x0415, "pl-PL"), Map.entry(0x0405, "cs-CZ"),
            Map.entry(0x041b, "sk-SK"), Map.entry(0x040e, "hu-HU"), Map.entry(0x0418, "ro-RO"),
            Map.entry(0x0419, "ru-RU"), Map.entry(0x0422, "uk-UA"), Map.entry(0x0402, "bg-BG"),
            Map.entry(0x0408, "el-GR"), Map.entry(0x041f, "tr-TR"), Map.entry(0x040d, "he-IL"),
            Map.entry(0x0401, "ar-SA"), Map.entry(0x0429, "fa-IR"), Map.entry(0x0420, "ur-PK"),
            Map.entry(0x0439, "hi-IN"), Map.entry(0x041e, "th-TH"), Map.entry(0x042a, "vi-VN"),
            Map.entry(0x0411, "ja-JP"), Map.entry(0x0412, "ko-KR"), Map.entry(0x0804, "zh-CN"),
            Map.entry(0x0404, "zh-TW"), Map.entry(0x0c04, "zh-HK"), Map.entry(0x1004, "zh-SG"),
            Map.entry(0x0421, "id-ID"), Map.entry(0x043e, "ms-MY"), Map.entry(0x0424, "sl-SI"),
            Map.entry(0x041a, "hr-HR"), Map.entry(0x081a, "sr-Latn-RS"), Map.entry(0x0c1a, "sr-Cyrl-RS"),
            Map.entry(0x0425, "et-EE"), Map.entry(0x0426, "lv-LV"), Map.entry(0x0427, "lt-LT"),
            Map.entry(0x040f, "is-IS"), Map.entry(0x0403, "ca-ES"), Map.entry(0x042d, "eu-ES"),
            Map.entry(0x0456, "gl-ES"), Map.entry(0x0436, "af-ZA"), Map.entry(0x0441, "sw-KE"));

    private Lcid() {}

    static String tag(int lcid) {
        return TAGS.get(lcid);
    }
}
