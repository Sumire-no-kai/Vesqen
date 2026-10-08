-- Public GitHub counters for release APKs, one snapshot per UTC day. GitHub's download_count is
-- cumulative and counts every fetch of the file (browser, in-app update, scripts, retries), so it
-- is not a count of people. Nothing here comes from or describes a visitor or user.
CREATE TABLE daily_downloads (
    day TEXT NOT NULL,
    release_tag TEXT NOT NULL,
    asset TEXT NOT NULL,
    downloads INTEGER NOT NULL CHECK(downloads >= 0),
    PRIMARY KEY(day, release_tag, asset)
) STRICT;
