package stirling.software.officeconvert.pdfa;

import java.nio.ByteBuffer;

final class CffRepair {

    private CffRepair() {}

    static byte[] realNumbers(byte[] cff) {
        try {
            byte[] out = cff.clone();
            ByteBuffer b = ByteBuffer.wrap(out);
            int at = out[2] & 0xFF;
            at = skipIndex(b, at);
            int[] top = index(b, at);
            boolean changed = false;
            for (int i = 0; i + 1 < top.length; i++) {
                changed |= dict(b, top[i], top[i + 1]);
                int[] priv = operands(b, top[i], top[i + 1], 18);
                if (priv != null && priv.length == 2) {
                    changed |= dict(b, priv[1], priv[1] + priv[0]);
                }
                int[] fdArray = operands(b, top[i], top[i + 1], 0x0C24);
                if (fdArray != null && fdArray.length == 1) {
                    int[] fds = index(b, fdArray[0]);
                    for (int k = 0; k + 1 < fds.length; k++) {
                        changed |= dict(b, fds[k], fds[k + 1]);
                        int[] p = operands(b, fds[k], fds[k + 1], 18);
                        if (p != null && p.length == 2) {
                            changed |= dict(b, p[1], p[1] + p[0]);
                        }
                    }
                }
            }
            return changed ? out : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int skipIndex(ByteBuffer b, int at) {
        int[] entries = index(b, at);
        return entries.length == 0 ? at + 2 : entries[entries.length - 1];
    }

    private static int[] index(ByteBuffer b, int at) {
        int count = b.getShort(at) & 0xFFFF;
        if (count == 0) {
            return new int[0];
        }
        int offSize = b.get(at + 2) & 0xFF;
        int base = at + 3 + (count + 1) * offSize - 1;
        int[] out = new int[count + 1];
        for (int i = 0; i <= count; i++) {
            int v = 0;
            for (int k = 0; k < offSize; k++) {
                v = v << 8 | b.get(at + 3 + i * offSize + k) & 0xFF;
            }
            out[i] = base + v;
        }
        return out;
    }

    private static int[] operands(ByteBuffer b, int start, int end, int op) {
        java.util.List<Integer> stack = new java.util.ArrayList<>();
        int at = start;
        while (at < end) {
            int b0 = b.get(at) & 0xFF;
            if (b0 <= 21) {
                int key = b0 == 12 ? 0x0C00 | b.get(at + 1) & 0xFF : b0;
                if (key == op) {
                    return stack.stream().mapToInt(Integer::intValue).toArray();
                }
                stack.clear();
                at += b0 == 12 ? 2 : 1;
            } else if (b0 == 28) {
                stack.add((int) b.getShort(at + 1));
                at += 3;
            } else if (b0 == 29) {
                stack.add(b.getInt(at + 1));
                at += 5;
            } else if (b0 == 30) {
                at = realEnd(b, at, end);
                stack.add(0);
            } else if (b0 >= 32 && b0 <= 246) {
                stack.add(b0 - 139);
                at++;
            } else if (b0 >= 247 && b0 <= 250) {
                stack.add((b0 - 247) * 256 + (b.get(at + 1) & 0xFF) + 108);
                at += 2;
            } else if (b0 >= 251 && b0 <= 254) {
                stack.add(-(b0 - 251) * 256 - (b.get(at + 1) & 0xFF) - 108);
                at += 2;
            } else {
                at++;
            }
        }
        return null;
    }

    private static int realEnd(ByteBuffer b, int at, int end) {
        int p = at + 1;
        while (p < end) {
            int v = b.get(p++) & 0xFF;
            if ((v & 0x0F) == 0x0F || (v >> 4) == 0x0F) {
                break;
            }
        }
        return p;
    }

    private static boolean dict(ByteBuffer b, int start, int end) {
        boolean changed = false;
        int at = start;
        while (at < end) {
            int b0 = b.get(at) & 0xFF;
            if (b0 == 30) {
                int stop = realEnd(b, at, end);
                if (!valid(b, at + 1, stop)) {
                    for (int p = at + 1; p < stop; p++) {
                        b.put(p, (byte) (p == stop - 1 ? 0x0F : 0x00));
                    }
                    if (stop - 1 == at + 1) {
                        b.put(at + 1, (byte) 0x0F);
                    }
                    changed = true;
                }
                at = stop;
            } else if (b0 == 12) {
                at += 2;
            } else if (b0 == 28) {
                at += 3;
            } else if (b0 == 29) {
                at += 5;
            } else if (b0 >= 247 && b0 <= 254) {
                at += 2;
            } else {
                at++;
            }
        }
        return changed;
    }

    private static boolean valid(ByteBuffer b, int start, int stop) {
        StringBuilder s = new StringBuilder();
        for (int p = start; p < stop; p++) {
            int v = b.get(p) & 0xFF;
            for (int nibble : new int[] {v >> 4, v & 0x0F}) {
                switch (nibble) {
                    case 0xA -> s.append('.');
                    case 0xB -> s.append('E');
                    case 0xC -> s.append("E-");
                    case 0xE -> s.append('-');
                    case 0xD -> s.append('?');
                    case 0xF -> {
                        return parses(s.toString());
                    }
                    default -> s.append((char) ('0' + nibble));
                }
            }
        }
        return parses(s.toString());
    }

    private static boolean parses(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
