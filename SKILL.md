---
name: done
description: >-
  Use when the user says "done", "finish", "complete", "/done", or asks to
  commit after finishing a task. Runs unit tests and e2e tests, then commits
  only if all pass. Calls good7ob-task-mcp to record completed tasks in the
  DEV project. No AI co-author signature in commits.
---

# Done — Test, Commit & Record

When invoked, follow this process in order. Do not skip steps.

## Step 0 — Local verification (MANDATORY, before anything else)

**Do not proceed past this step unless every applicable check is green.** If
a check is blocked (e.g. no Docker daemon, DEV unreachable), stop and tell the
user — do not commit partial verification.

### 0a — Backend changes

If any file under `src/main/java/` or `src/main/resources/` was changed:

```bash
cd /Users/user/Documents/company/backend
# Compile first — catches syntax errors before a slow Docker build
mvn compile -q 2>&1 | tail -5
# Rebuild + restart the local container
docker compose -f docker-compose.local.yml build backend 2>&1 | tail -3
docker compose -f docker-compose.local.yml up -d --force-recreate backend 2>&1 | tail -3
# Wait for healthy
until curl -sf http://localhost:9080/actuator/health > /dev/null; do sleep 2; done
```

Then smoke-test the affected endpoint(s) with `curl` against `localhost:9080`
and assert HTTP status + response body matches the intended behavior.
Record the exact `curl` command used in the commit message or PR body.

### 0b — Frontend changes

If any file under `/Users/user/Documents/company/front-web/src/` was changed:

- Start (or reuse) `npm run dev` on `localhost:9527`
- Open the affected view in a browser and exercise the change manually
- For quick one-off checks, inject DOM/CSS/JS via DevTools — do NOT commit
  and deploy to DEV just to verify
- Viewport is 720px Retina (mobile). To check desktop UI, force-show via JS:
  `document.querySelector('.navbar-desktop').style.display = 'flex'`

If a UI change cannot be verified locally (e.g. depends on unreleased API),
**say so explicitly in your response and ask the user** before continuing.
Type checking and unit tests do not count — they verify correctness of
code, not correctness of behavior.

### 0c — Confirm before proceeding

Only after every applicable check above has passed should you move to Step 1.
Do not silently skip this section because "tests will catch it" — the backend
and e2e suites here have pre-existing flake/compile issues and will not
substitute for local verification.

## Step 1 — Identify changes

```bash
git diff --name-only HEAD
```

Also check frontend repo if changes were made there:
```bash
cd /Users/user/Documents/company/front-web && git diff --name-only HEAD
```

## Step 2 — Run unit tests for every changed file

**Backend (Java) — find and run relevant test class:**
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 11) mvn test -pl . \
  -Dtest="*<AffectedClass>*" -DfailIfNoTests=false
```

**Frontend (Vue/TS) — run vitest for affected spec files:**
```bash
cd /Users/user/Documents/company/front-web
npx vitest run src/path/to/__tests__/Affected.spec.ts
```

If no unit test exists for changed code → write one first, then run it.

## Step 3 — Write and run e2e tests for affected features

Before committing, you MUST ensure e2e test coverage exists and passes locally.

### 3a — Write e2e tests if missing

For every changed feature or API behavior, check if a matching e2e scenario
exists in `e2e/features/`. If not, **write one first**:

1. Add scenario(s) to the relevant `.feature` file (or create a new one)
2. Add step definitions in `e2e/steps/` if needed
3. For backend-only API changes (validation, error handling), write API-level
   e2e scenarios that call the endpoint directly and assert status codes / response body

### 3b — Run e2e tests locally and verify they pass

```bash
cd /Users/user/Documents/company/front-web/e2e
npx bddgen && \
npx playwright test --grep "@relevant-tag" --config playwright.config.ts
```

If Docker backend needs rebuilding for the new code:
```bash
cd /Users/user/Documents/company/backend
docker compose -f docker-compose.local.yml build backend && \
docker compose -f docker-compose.local.yml up -d --force-recreate backend
# Wait for healthy status before running tests
```

**All e2e tests must pass locally before proceeding to commit.**
If tests fail → fix the issue, do NOT comment out or skip tests.

## Step 4 — Only commit if ALL tests pass (unit + e2e)

Commit backend and frontend separately (they are different git repos):

```bash
# Backend
cd /Users/user/Documents/company/backend
git add <specific files — never git add -A>
git commit -m "<type>(<scope>): <description>"

# Frontend (if changed)
cd /Users/user/Documents/company/front-web
git add <specific files — never git add -A>
git commit -m "<type>(<scope>): <description>"
```

**Commit rules:**
- Conventional commits format: `type(scope): description`
- Types: `feat`, `fix`, `test`, `refactor`, `chore`, `docs`
- **NO** `Co-Authored-By` line
- **NO** AI attribution of any kind

## Step 5 — Record completed tasks in DEV project

After a successful commit, use the `good7ob-task-mcp` skill to create or
update tasks in the DEV project (project ID 1, endpoint `https://api-dev.good7ob.net/mcp`).

For each logical change committed:
- If a matching task already exists → mark it `completed` with `progress: 100`
- If no matching task exists → create a new `completed` task describing what was done

## Step 6 — Push and deploy (if user requests)

```bash
# Push both repos
cd /Users/user/Documents/company/backend && git push origin dev
cd /Users/user/Documents/company/front-web && git push origin dev

# Trigger deployments
gh workflow run "deploy-ecs-dev.yml" --ref dev -f environment=dev --repo good7ob/backend
gh workflow run "deploy-s3-dev.yml" --ref dev --repo good7ob/front-web
```

Only push and deploy when the user explicitly requests it.

## Coverage requirements

| Change type | Required before commit |
|-------------|----------------------|
| New backend service/controller method | Unit test in `*ServiceTest` or `*ControllerTest` **+** e2e scenario |
| Backend API validation / error handling | Unit test **+** API-level e2e scenario (call endpoint, assert status/body) |
| New/changed frontend component | `*.spec.ts` in `__tests__/` **+** e2e scenario |
| New/changed Pinia store action | Store `*.spec.ts` |
| Any user-facing feature | e2e scenario in `e2e/features/` |
| Bug fix | Test that reproduces the bug **+** e2e scenario if user-facing |

**All tests (unit + e2e) must pass locally before commit. No exceptions.**
If tests fail → fix the issue, do NOT comment out or skip tests.

## What counts as a REAL test (MANDATORY)

A test only counts as "real" if it would have **failed** when the bug was
present. Cosmetic checks that always pass — verifying an element exists,
a page loads, a button is rendered — do **not** satisfy the coverage
requirement above. The PRD module shipped with broken Generate / Save /
Export because every existing scenario was cosmetic.

For every user-facing button or workflow, the e2e scenario MUST:

1. **Trigger the action** — actually click the button, fill the input,
   submit the form. Don't just assert the button is `visible`.
2. **Assert the side effect** — check the rendered output, the network
   response body, the persisted state, or the success toast. Verify the
   thing the button is supposed to do **actually happened**.
3. **Use a fresh fixture per run** — seed only what's required to set up
   the precondition (e.g. an existing PRD for the "edit" scenario), and
   clean up after. Don't pre-seed the artefact the button is supposed to
   produce — that hides bugs in the produce step.
4. **Check the data, not just the status** — `200 OK` with empty/wrong
   body still passes a status check. Assert response shape and key fields.

### Anti-patterns (reject these)

- ❌ `Then 页面应显示编辑按钮` (only checks visibility — won't catch a
  broken click handler)
- ❌ `When 用户访问PRD管理页面 / Then PRD页面应正常显示` as the only
  coverage for a generate/save/export workflow
- ❌ Pre-seeding via API the exact artefact the button is meant to create
  (this skips the button entirely)
- ❌ Asserting `response.code === 200` without inspecting `response.data`
- ❌ Verifying a downloaded file by extension or magic bytes alone — a
  PDF that's actually HTML wrapped in a `%PDF-` prefix still passes
  `startsWith("%PDF-")`, and `content-type: application/pdf` is set by
  whoever wrote the response header, not by the bytes. Always extract
  text from the artefact and compare a short anchor (first H1 + first
  H2 or a known unique phrase) against the live source the user sees.

### Format vs content (separate rules)

A test that opens a downloaded file must answer two questions, not one:

1. **Is the format valid?** Magic bytes + structural signature
   (`%PDF-`, `PK\x03\x04` + `[Content_Types].xml`, etc.).
2. **Is the content right?** Extract text and require chunks of the
   source content appear inside it. For PDF use `pdftotext`; for DOCX
   `unzip -p file word/document.xml` and strip tags; for markdown the
   download IS the text.

If a test only answers (1), a backend that ships an HTML error page
under a `.pdf` filename will pass — exactly the bug class we shipped
to PROD before this rule was added.

### Pattern to follow

```gherkin
Scenario: 生成 PRD 后内容包含用户提问的关键词
  Given 用户已登录系统
  And 用户已发送消息 "生成一份小型数据库构建的 PRD"
  When 用户点击 "生成 PRD" 按钮
  Then 右侧预览区域应显示生成的 PRD
  And 生成内容应包含关键词 "数据库" 或 "data"
  And 生成内容长度应大于 500 字符
```

If a feature has no real-action scenario, **block the commit** and tell the
user — adding cosmetic tests does not unblock.

## Pre-commit gate for new user-facing code (MANDATORY)

When the diff touches a new button, form submission, dropdown action, or
similar user-facing control in `src/views/`, `src/components/`, or a
backend controller method, run this check **before** Step 4:

1. Identify every new or changed user-triggered action.
2. For each one, search `e2e/features/**` for a scenario that clicks /
   submits / triggers it AND asserts a side effect (rendered output,
   response body, persisted state, success toast).
3. If any action has no matching scenario, **stop and write one before
   committing**. The cosmetic-test rubric above defines what counts.

Audit baseline (2026-04-27): only ~17% of existing scenarios are real.
The PRD module shipped with broken Generate / Save / Export despite
having BDD coverage because every scenario only checked element
visibility. New code is held to a higher bar to stop the rot.

Do not silently commit a UI change with the excuse "tests already exist
for this view" — verify they're REAL, per the rubric.
