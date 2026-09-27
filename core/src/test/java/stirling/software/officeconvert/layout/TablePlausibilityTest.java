package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.TableDetection;

class TablePlausibilityTest {

    private static TableDetection.Found table(String[][] rows, float[] sizes, List<PdfWord> words) {
        List<TableDetection.FoundCell> cells = new ArrayList<>();
        float[] edges = new float[rows[0].length + 1];
        for (int c = 0; c <= rows[0].length; c++) {
            edges[c] = 100 + c * 150;
        }
        for (int r = 0; r < rows.length; r++) {
            float top = 100 + r * 12;
            for (int c = 0; c < rows[r].length; c++) {
                cells.add(new TableDetection.FoundCell(r, c, 1, 1, edges[c], top, edges[c + 1], top + 12, false, false,
                        false, false, null, TableDetection.HAlign.LEFT, TableDetection.VAlign.TOP, rows[r][c]));
                if (!rows[r][c].isEmpty()) {
                    float size = sizes[c];
                    words.add(new PdfWord(rows[r][c], edges[c] + 2, edges[c] + 60, top + 1, top + 11, top + 9, size,
                            false, false, 0, size * 0.25f, new float[0], new float[0], new String[0]));
                }
            }
        }
        return new TableDetection.Found(rows.length, rows[0].length, edges, false, 0, 0, cells, edges[0], 100,
                edges[edges.length - 1], 100 + rows.length * 12);
    }

    @Test
    void acceptsAShortBorderlessTable() {
        List<PdfWord> words = new ArrayList<>();
        String[][] rows = {{"Method", "F1"}, {"Heuristic", "0.68"}, {"CNN", "0.82"}, {"Ensemble", "0.87"}};
        assertTrue(TablePlausibility.plausible(table(rows, new float[] {9, 9}, words), words));
    }

    @Test
    void rejectsTwoColumnsOfRunningText() {
        String[][] rows = {
            {"Viele Jahre lang war der Movie", "Button. Zudem hat Windows Clip-"},
            {"Maker das in Windows integrierte", "champ ins Kontextmenue gepackt."},
            {"Programm fuer bewegte Bilder. 2017", "Nach einem Rechtsklick auf eine"},
            {"stellte Microsoft den Support fuer", "Videodatei ist im Menue der Eintrag"}
        };
        List<PdfWord> words = new ArrayList<>();
        assertTrue(TablePlausibility.textColumns(table(rows, new float[] {9, 9}, words)));
    }

    @Test
    void rejectsRowsPairingDifferentTextSizes() {
        List<PdfWord> words = new ArrayList<>();
        String[][] rows = {{"DVD-Laufwerk", "Die besten Tools"}, {"die Inhalte", "Office-Suiten"}, {"zugreifen", "KI und Office"}};
        assertFalse(TablePlausibility.plausible(table(rows, new float[] {9, 5.3f}, words), words));
    }
}
