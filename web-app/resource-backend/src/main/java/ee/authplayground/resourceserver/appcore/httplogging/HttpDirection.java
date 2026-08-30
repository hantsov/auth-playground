package ee.authplayground.resourceserver.appcore.httplogging;

/**
 * Which way an HTTP message was travelling.
 *
 * <p>The tags are padded to a common width so the {@code [IN  - REQ]} /
 * {@code [OUT - RSP]} column stays aligned down the log. That alignment is the
 * whole reason these lines are scannable, so the padding is load-bearing rather
 * than cosmetic — do not "tidy" it away.
 */
public enum HttpDirection {

    /** Arrived at this service. */
    INCOMING("IN "),

    /** Sent by this service to somebody else. */
    OUTGOING("OUT");

    private final String tag;

    HttpDirection(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
