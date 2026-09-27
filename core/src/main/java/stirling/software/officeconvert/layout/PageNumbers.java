package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class PageNumbers {

    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[\\p{Punct}\\u2013\\u2014]+|[\\p{Punct}\\u2013\\u2014]+$");

    private PageNumbers() {}

    static List<Integer> in(Line seg) {
        List<Integer> out = new ArrayList<>();
        List<Word> words = seg.words;
        if (words.size() > 6) {
            return out;
        }
        for (int i = 0; i < words.size(); i++) {
            String t = EDGE_PUNCTUATION.matcher(words.get(i).text).replaceAll("");
            if (!t.matches("[1-9][0-9]{0,3}")) {
                continue;
            }
            boolean edge = i == 0 || i == words.size() - 1;
            boolean onlyPunctuation = true;
            for (int k = 0; k < words.size(); k++) {
                if (k != i && words.get(k).text.chars().anyMatch(Character::isLetterOrDigit)) {
                    onlyPunctuation = false;
                }
            }
            if (edge || onlyPunctuation) {
                out.add(Integer.parseInt(t));
            }
        }
        return out;
    }
}
