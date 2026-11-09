# POSIX awk. Only replace complete, known SPEAKER point sequences.
function finish(    text, points, p, count, sig, normalized, first, last, head, tail, indent, lines, n, i) {
    text = block
    sig = ""
    count = 0
    first = 0
    last = 0
    p = 0
    while (match(text, /<point>[[:space:]]*[0-9]+[[:space:]]*,[[:space:]]*-?[0-9]+[[:space:]]*<\/point>/)) {
        if (!first) first = p + RSTART
        last = p + RSTART + RLENGTH - 1
        normalized = substr(text, RSTART, RLENGTH)
        gsub(/<[^>]*>|[[:space:]]/, "", normalized)
        sig = sig (count ? ";" : "") normalized
        count++
        p += RSTART + RLENGTH - 1
        text = substr(text, RSTART + RLENGTH)
    }
    # A malformed/unrecognized point invalidates the entire candidate.
    text = block
    points = gsub(/<point([[:space:]>])/, "&", text)
    if (speaker && points == count && (sig == stock || sig == old || sig == gentle)) {
        head = substr(block, 1, first - 1)
        tail = substr(block, last + 1)
        indent = "    "
        n = split(head, lines, "\n")
        if (lines[n] ~ /^[ \t]*$/) indent = lines[n]
        printf "%s<point>1,-8500</point>\n", head
        printf "%s<point>5,-7000</point>\n", indent
        printf "%s<point>10,-5800</point>\n", indent
        printf "%s<point>20,-4000</point>\n", indent
        printf "%s<point>60,-1700</point>\n", indent
        printf "%s<point>100,0</point>%s", indent, tail
        patched++
    } else printf "%s", block
}
BEGIN {
    stock = "1,-6300;13,-4650;20,-3800;27,-2950;33,-2500;40,-2200;47,-1900;53,-1600;60,-1300;100,0"
    old = "1,-8500;13,-6000;20,-3800;27,-2950;33,-2500;40,-2200;47,-1900;53,-1600;60,-1300;100,0"
    gentle = "1,-8500;5,-7000;10,-5800;20,-4000;60,-1700;100,0"
}
{
    if (!inside && $0 ~ /^[ \t]*<volume([ \t>])/ && $0 !~ /\/>/) {
        # Opening tag must fit on this line; unfamiliar formats are not patched.
        inside = 1
        speaker = ($0 ~ /deviceCategory[ \t]*=[ \t]*"DEVICE_CATEGORY_SPEAKER"/ && $0 ~ />/)
        block = ""
    }
    if (inside) {
        block = block $0 "\n"
        if ($0 ~ /<\/volume>/) {
            finish()
            inside = 0
            block = ""
        }
    } else print
}
END {
    if (inside) {
        printf "%s", block
        print "ERROR: unclosed volume block" > "/dev/stderr"
        exit 3
    }
    if (!patched) {
        print "ERROR: no complete known speaker curve matched" > "/dev/stderr"
        exit 2
    }
    print "Matched speaker blocks: " patched > "/dev/stderr"
}
