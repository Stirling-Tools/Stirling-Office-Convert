package stirling.software.officeconvert.table;

record LineRange(int first, int last) {

    LineRange join(LineRange next) {
        return new LineRange(first, next.last);
    }
}
