package android.net;

/** Host stand-in for android.net.Uri covering only what GooglePlayCheckout.manageUri calls. */
public final class Uri {
    private final String value;

    private Uri(String value) { this.value = value; }

    public static Uri parse(String value) { return new Uri(value); }

    public Builder buildUpon() { return new Builder(value); }

    @Override public String toString() { return value; }

    public static final class Builder {
        private final StringBuilder value;
        private boolean query;

        private Builder(String base) {
            value = new StringBuilder(base);
            query = base.indexOf('?') >= 0;
        }

        public Builder appendQueryParameter(String key, String parameter) {
            value.append(query ? '&' : '?').append(key).append('=').append(parameter);
            query = true;
            return this;
        }

        public Uri build() { return new Uri(value.toString()); }
    }
}
