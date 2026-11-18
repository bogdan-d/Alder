// Takes a crash report from Alder (POST, plain text) and files it as a GitHub issue labelled "crash".
// The same error again adds a comment to its open issue, so one bug stays one issue with a count.
// Secret: GITHUB_TOKEN, a fine-grained token with Issues: read and write on the Alder repo only.
const REPO = "aradhyas8/Alder";
const MAX = 8000;

export default {
  async fetch(request, env) {
    if (request.method !== "POST" || request.headers.get("X-Alder") !== "1") return new Response("", { status: 404 });
    const ip = request.headers.get("CF-Connecting-IP") || "?";
    if (!(await env.LIMIT.limit({ key: ip })).success) return new Response("slow down", { status: 429 });
    const report = (await request.text()).slice(0, MAX);
    // The report's first line after "Thread:" is the error, e.g. "java.lang.IllegalStateException: …".
    const error = (report.split(/\r?\n/).find((l, i, all) => i > 0 && all[i - 1].startsWith("Thread:")) || "").trim();
    if (!report.startsWith("Alder ") || !error) return new Response("not a report", { status: 400 });
    const version = report.split("\n")[0];
    const title = ("Crash: " + error).slice(0, 120);
    const body = "```\n" + report.replaceAll("```", "'''") + "\n```";

    const gh = (path, init = {}) => fetch("https://api.github.com" + path, {
      ...init,
      headers: { Authorization: `Bearer ${env.GITHUB_TOKEN.trim()}`, Accept: "application/vnd.github+json",
        "User-Agent": "alder-crash-worker", "Content-Type": "application/json" },
    });
    const q = encodeURIComponent(`repo:${REPO} is:issue is:open label:crash in:title "${title.replaceAll('"', "")}"`);
    const same = (await (await gh(`/search/issues?q=${q}&per_page=1`)).json()).items?.[0];
    const res = same
      ? await gh(`/repos/${REPO}/issues/${same.number}/comments`, { method: "POST", body: JSON.stringify({ body: `Again on ${version}:\n\n${body}` }) })
      : await gh(`/repos/${REPO}/issues`, { method: "POST", body: JSON.stringify({ title, body, labels: ["crash"] }) });
    // GitHub's status on failure (401 bad token, 403/404 token can't write issues on this repo); never its body.
    return new Response(res.ok ? "ok" : `github ${res.status}`, { status: res.ok ? 200 : 502 });
  },
};
