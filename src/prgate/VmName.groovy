package prgate

/** Builds cloud-safe, unique, recognisable names for ephemeral CI VMs. */
class VmName implements Serializable {
    static final int MAX_LENGTH = 63

    static String forBuild(String prefix, String job, Object buildNumber, String suffix = '') {
        String base = [prefix, job, buildNumber, suffix].findAll { it?.toString() }.join('-')
        String clean = base.toLowerCase()
                           .replaceAll(/[^a-z0-9-]+/, '-')
                           .replaceAll(/-{2,}/, '-')
                           .replaceAll(/^-|-$/, '')
        if (clean.length() <= MAX_LENGTH) {
            return clean
        }
        // Keep the tail (build number / suffix) since that is what makes it unique.
        String tail = "${buildNumber}${suffix ? '-' + suffix : ''}".toLowerCase().replaceAll(/[^a-z0-9-]+/, '-')
        String head = clean.substring(0, MAX_LENGTH - tail.length() - 1).replaceAll(/-$/, '')
        return "${head}-${tail}"
    }
}
