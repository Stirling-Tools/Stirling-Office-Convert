package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class PageNumbers {

    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,3}");

    private static final String PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~–—";

    private PageNumbers() {}

    static List<Integer> in(Line seg) {
        List<Integer> out = new ArrayList<>();
        List<Word> words = seg.words;
        if (words.size() > 6) {
            return out;
        }
        for (int i = 0; i < words.size(); i++) {
            String t = withoutEdgePunctuation(words.get(i).text);
            if (!NUMBER.matcher(t).matches()) {
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

    // A loop, not a regex: an end-anchored run retried at every offset is quadratic on long punctuation.
    private static String withoutEdgePunctuation(String text) {
        int from = 0;
        int to = text.length();
        while (from < to && PUNCTUATION.indexOf(text.charAt(from)) >= 0) {
            from++;
        }
        while (to > from && PUNCTUATION.indexOf(text.charAt(to - 1)) >= 0) {
            to--;
        }
        return text.substring(from, to);
    }
}
