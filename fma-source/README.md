# Free Music Archive source

This is a Relay source extension for Free Music Archive's public search pages. It makes one bounded public HTML request per browse or search, extracts the visible track cards, and sends Relay FMA's own public `/stream/` redirect URL.

It does not require or send credentials, scrape logged-in-only pages, bypass rate limits, fetch audio itself, or offer a download command. FMA controls availability; track licences and attribution terms are per work and should be checked on FMA before reuse.

Search fields in Relay (title, artist, album) currently become an FMA all-text search term because the public FMA search form supports one general query.

