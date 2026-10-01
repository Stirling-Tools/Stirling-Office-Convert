package stirling.software.officeconvert.topdf.rtf;

final class Group {

    Dest dest = Dest.NORMAL;
    CharProps chp;
    ParaProps pap;
    int uc = 1;
    Story story;
    Wrap wrap;
    boolean listText;
    boolean nestProps;
    boolean inNote;
    boolean upr;
    boolean uprSkipped;
    Dest uprDest;
    StringBuilder text;
    String key;
    Object payload;
    Shape shape;
    Sp sp;
    Field field;
    Note note;
    String header;
    boolean textbox;
    boolean background;
    boolean math;
    RtfMath.Node mathNode;

    static final class Sp {
        final StringBuilder name = new StringBuilder();
        final StringBuilder value = new StringBuilder();
    }

    static final class Field {
        final StringBuilder inst = new StringBuilder();
        boolean result;
        Object resultPara;
        int resultLength;
    }

    static final class Note {
        final int id;
        final boolean custom;
        final Story parent;
        boolean endnote;

        Note(int id, boolean custom, Story parent) {
            this.id = id;
            this.custom = custom;
            this.parent = parent;
        }
    }

    Group child() {
        Group g = new Group();
        g.dest = dest;
        g.chp = chp.copy();
        g.pap = pap.copy();
        g.uc = uc;
        g.story = story;
        g.wrap = wrap;
        g.listText = listText;
        g.nestProps = nestProps;
        g.inNote = inNote;
        g.payload = payload;
        g.shape = shape;
        g.sp = sp;
        g.field = field;
        g.text = dest == Dest.NORMAL ? null : text;
        g.key = key;
        g.background = background;
        g.math = math;
        g.mathNode = mathNode;
        return g;
    }
}
