# JFetch

Spring Boot app that pulls jobs from **JSearch (OpenWeb Ninja) via RapidAPI**, keeps the ones that
mention a keyword (default `java`) and fall within an experience range, and lists them with a link to
the listing and to the company's AmbitionBox page. It only builds the AmbitionBox URL and never fetches it.

## Run

1. Open `.env` in the project folder (next to `pom.xml`) and paste your RapidAPI key, then save:
   ```
   RAPIDAPI_KEY=your-rapidapi-key
   ```
   Subscribe to "JSearch" by letscrape-6bRBa3QguO5 on RapidAPI to get a key. `.env` is gitignored. If
   it's missing, copy `.env.example` to `.env`.
2. Start the app and open http://localhost:8080:
   ```bash
   ./mvnw spring-boot:run
   ```

`.env` is read from the folder you start the app in, so start it from the project folder. That's the
default for IntelliJ run configurations. An exported `RAPIDAPI_KEY` environment variable overrides the
file.

Data is stored in `./data/jfetch.mv.db` (H2). Console: http://localhost:8080/h2-console
(JDBC URL `jdbc:h2:file:./data/jfetch`, user `sa`, empty password).

## How it behaves

- **Search criteria are applied when jobs are fetched.** Each new job that matches the keyword and
  experience range is stored as **Pending**. Every other new job is stored as **Criteria failed** and
  listed on its own page, so you can spot parsing mistakes and move a job back to Pending.
- **Only new listings:** any `job_id` already in the DB is ignored, whatever its status. A search calls
  JSearch's `/search-v2` endpoint one page (~10 jobs) at a time, following the `cursor` it returns, until
  it has *N × 10* new jobs. It also stops when JSearch runs out of results or after
  `jsearch.max-scan-pages` pages (default 15).
- **Cost:** each page is one billed request. Repeat searches cost more, because the pages you've already
  seen are fetched again before the new ones. To keep repeat searches cheap, use "Date posted: Past 3
  days" or "Past week" so JSearch only returns recent jobs.
- **Caching:** repeating the exact same search (query, country, date posted, pages, criteria) is served
  from H2 without calling JSearch, until the search is expired.
- **Skip all pending** marks every Pending job as Skipped and expires every cached search, so the next
  search (or **Re-fetch** on the Search page) calls JSearch again.
- **Pagination** (10 per page) runs over stored rows and never calls the API.
- **Experience:** uses JSearch's `required_experience_in_months` when present. Otherwise it parses the
  title, qualifications and description ("0-1 years", "2-4 yrs", "3+ years", "minimum 2 years"). In the
  description, "years" only counts with "experience" nearby; in the title it always counts. Only
  **mandatory** requirements count:
  - lines under a "Nice to have", "Good to have", "Preferred", "Bonus" or "Desirable" heading are
    ignored until the next heading;
  - so is any sentence containing "is a plus", "preferred", "advantage" or similar.

  **Freshers:** if the listing says freshers, fresh graduates or entry level are accepted, the job counts
  as starting at 0 years, whatever years it also states. "Freshers need not apply" and similar don't
  count. A job matches if its requirement overlaps your range. Jobs with no detectable mandatory
  experience fail unless you tick "include unknown experience".
- **Keyword** matching is case-insensitive and word-aware, so `java` doesn't match `JavaScript`.
- The Pending, Applied, Skipped and Criteria failed pages have optional view filters. These only change
  what's shown, never the stored status.
