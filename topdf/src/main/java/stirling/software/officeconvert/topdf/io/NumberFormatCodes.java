package stirling.software.officeconvert.topdf.io;

public final class NumberFormatCodes {

    private NumberFormatCodes() {}

    public static String ooxml(int nfc) {
        return switch (nfc) {
            case 0 -> "decimal";
            case 1 -> "upperRoman";
            case 2 -> "lowerRoman";
            case 3 -> "upperLetter";
            case 4 -> "lowerLetter";
            case 5 -> "ordinal";
            case 6 -> "cardinalText";
            case 7 -> "ordinalText";
            case 8 -> "hex";
            case 9 -> "chicago";
            case 10 -> "ideographDigital";
            case 11 -> "japaneseCounting";
            case 12 -> "aiueo";
            case 13 -> "iroha";
            case 14 -> "decimalFullWidth";
            case 15 -> "decimalHalfWidth";
            case 16 -> "japaneseLegal";
            case 17 -> "japaneseDigitalTenThousand";
            case 18 -> "decimalEnclosedCircle";
            case 19 -> "decimalFullWidth2";
            case 20 -> "aiueoFullWidth";
            case 21 -> "irohaFullWidth";
            case 22 -> "decimalZero";
            case 23 -> "bullet";
            case 24 -> "ganada";
            case 25 -> "chosung";
            case 26 -> "decimalEnclosedFullstop";
            case 27 -> "decimalEnclosedParen";
            case 28 -> "decimalEnclosedCircleChinese";
            case 29 -> "ideographEnclosedCircle";
            case 30 -> "ideographTraditional";
            case 31 -> "ideographZodiac";
            case 32 -> "ideographZodiacTraditional";
            case 33 -> "taiwaneseCounting";
            case 34 -> "ideographLegalTraditional";
            case 35 -> "taiwaneseCountingThousand";
            case 36 -> "taiwaneseDigital";
            case 37 -> "chineseCounting";
            case 38 -> "chineseLegalSimplified";
            case 39 -> "chineseCountingThousand";
            case 41 -> "koreanDigital";
            case 42 -> "koreanCounting";
            case 43 -> "koreanLegal";
            case 44 -> "koreanDigital2";
            case 45 -> "hebrew1";
            case 46 -> "arabicAlpha";
            case 47 -> "hebrew2";
            case 48 -> "arabicAbjad";
            case 49 -> "hindiVowels";
            case 50 -> "hindiConsonants";
            case 51 -> "hindiNumbers";
            case 52 -> "hindiCounting";
            case 53 -> "thaiLetters";
            case 54 -> "thaiNumbers";
            case 55 -> "thaiCounting";
            case 56 -> "vietnameseCounting";
            case 57 -> "numberInDash";
            case 58 -> "russianLower";
            case 59 -> "russianUpper";
            case 255 -> "none";
            default -> "decimal";
        };
    }
}
