import java.util.*;

//__SOLUTION__

public class Main {

    public static void main(String[] args) throws Exception {
        String raw = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        List<Object> argv = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String part : split(raw)) {
                argv.add(parse(part));
            }
        }
        Solution sol = new Solution();
        //__CALL__
        //__PRINT__
    }

    static List<String> split(String src) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (quoted) {
                cur.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    cur.append(src.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
                cur.append(c);
                continue;
            }
            if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (!cur.toString().trim().isEmpty()) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object parse(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("[")) {
            List<Object> list = new ArrayList<>();
            String inner = s.substring(1, s.length() - 1).trim();
            if (!inner.isEmpty()) {
                for (String part : split(inner)) {
                    list.add(parse(part));
                }
            }
            return list;
        }
        if (s.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < s.length() - 1; i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length() - 1) {
                    char n = s.charAt(++i);
                    sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        if (s.equals("true")) {
            return Boolean.TRUE;
        }
        if (s.equals("false")) {
            return Boolean.FALSE;
        }
        if (s.indexOf('.') >= 0) {
            return Double.parseDouble(s);
        }
        return Long.parseLong(s);
    }

    static int toInt(Object o) {
        return ((Number) o).intValue();
    }

    static long toLong(Object o) {
        return ((Number) o).longValue();
    }

    static String toStr(Object o) {
        return (String) o;
    }

    static boolean toBool(Object o) {
        return (Boolean) o;
    }

    @SuppressWarnings("unchecked")
    static int[] toInts(Object o) {
        List<Object> l = (List<Object>) o;
        int[] r = new int[l.size()];
        for (int i = 0; i < l.size(); i++) {
            r[i] = toInt(l.get(i));
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    static long[] toLongs(Object o) {
        List<Object> l = (List<Object>) o;
        long[] r = new long[l.size()];
        for (int i = 0; i < l.size(); i++) {
            r[i] = toLong(l.get(i));
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    static String[] toStrs(Object o) {
        List<Object> l = (List<Object>) o;
        String[] r = new String[l.size()];
        for (int i = 0; i < l.size(); i++) {
            r[i] = toStr(l.get(i));
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    static int[][] toMatrix(Object o) {
        List<Object> l = (List<Object>) o;
        int[][] r = new int[l.size()][];
        for (int i = 0; i < l.size(); i++) {
            r[i] = toInts(l.get(i));
        }
        return r;
    }

    static String fmt(int v) {
        return Integer.toString(v);
    }

    static String fmt(long v) {
        return Long.toString(v);
    }

    static String fmt(boolean v) {
        return Boolean.toString(v);
    }

    static String fmt(String v) {
        return v;
    }

    static String fmt(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    static String fmt(long[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    static String fmt(String[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    static String fmt(int[][] m) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < m.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(fmt(m[i]));
        }
        return sb.append(']').toString();
    }
}
