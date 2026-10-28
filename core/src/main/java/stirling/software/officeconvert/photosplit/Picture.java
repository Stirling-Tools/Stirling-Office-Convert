package stirling.software.officeconvert.photosplit;

interface Picture {

    int width();

    int height();

    int rgb(int x, int y);

    boolean gray();
}
