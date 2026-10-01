package stirling.software.officeconvert.topdf.rtf;

import java.util.HashMap;
import java.util.Map;

final class Langs {

    private static final Map<Integer, String> TAGS = new HashMap<>();

    static {
        String[] pairs = {"1025", "ar-SA", "1026", "bg-BG", "1027", "ca-ES", "1028", "zh-TW", "1029", "cs-CZ", "1030",
            "da-DK", "1031", "de-DE", "1032", "el-GR", "1033", "en-US", "1034", "es-ES", "1035", "fi-FI", "1036",
            "fr-FR", "1037", "he-IL", "1038", "hu-HU", "1039", "is-IS", "1040", "it-IT", "1041", "ja-JP", "1042",
            "ko-KR", "1043", "nl-NL", "1044", "nb-NO", "1045", "pl-PL", "1046", "pt-BR", "1048", "ro-RO", "1049",
            "ru-RU", "1050", "hr-HR", "1051", "sk-SK", "1052", "sq-AL", "1053", "sv-SE", "1054", "th-TH", "1055",
            "tr-TR", "1056", "ur-PK", "1057", "id-ID", "1058", "uk-UA", "1059", "be-BY", "1060", "sl-SI", "1061",
            "et-EE", "1062", "lv-LV", "1063", "lt-LT", "1065", "fa-IR", "1066", "vi-VN", "1067", "hy-AM", "1068",
            "az-Latn-AZ", "1069", "eu-ES", "1071", "mk-MK", "1078", "af-ZA", "1079", "ka-GE", "1081", "hi-IN", "1086",
            "ms-MY", "1087", "kk-KZ", "1089", "sw-KE", "1093", "bn-IN", "1094", "pa-IN", "1095", "gu-IN", "1097",
            "ta-IN", "1098", "te-IN", "1099", "kn-IN", "1100", "ml-IN", "1102", "mr-IN", "1110", "gl-ES", "1124",
            "fil-PH", "2052", "zh-CN", "2055", "de-CH", "2057", "en-GB", "2058", "es-MX", "2060", "fr-BE", "2064",
            "it-CH", "2067", "nl-BE", "2068", "nn-NO", "2070", "pt-PT", "3076", "zh-HK", "3079", "de-AT", "3081",
            "en-AU", "3082", "es-ES", "3084", "fr-CA", "4100", "zh-SG", "4105", "en-CA", "5129", "en-NZ", "6153",
            "en-IE", "7177", "en-ZA", "1036", "fr-FR", "1047", "rm-CH", "11273", "en-TT", "16393", "en-IN"};
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            TAGS.put(Integer.parseInt(pairs[i]), pairs[i + 1]);
        }
    }

    private Langs() {}

    static String tag(int lcid) {
        return TAGS.get(lcid);
    }
}
