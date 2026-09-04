# Demo Runbook — PAS Port Logistics (Deployment Plan + Business Demo)

## Mục lục / Table of Contents

1. [Tài khoản demo (password = username)](#tài-khoản-demo-password--username)
2. [Chuẩn bị trình diễn](#7-chuẩn-bị-trình-diễn--demo-preparation)
3. [Quy trình demo chi tiết — 8 bước §3 (fail-first per rule)](#8-quy-trình-demo-chi-tiết--8-bước-3-fail-first-per-rule--detailed-demo-script)
   - [Bước 0 — Quản trị hệ thống](#bước-0--quản-trị-hệ-thống--system-administration)
   - [Bước 1 — Tạo khách hàng & hợp đồng](#bước-1--tạo-khách-hàng--hợp-đồng--create-customer--contract)
   - [Bước 2 — Phê duyệt hợp đồng](#bước-2--phê-duyệt-hợp-đồng--contract-approval)
   - [Bước 3+4 — Bảng giá & hiệu lực](#bước-34--bảng-giá--hiệu-lực--price-lists--effectiveness)
   - [Bước 5 — Sản lượng & khóa kỳ](#bước-5--sản-lượng--khóa-kỳ--volume--period-lock)
   - [Bước 6 — Lập bảng thanh toán](#bước-6--lập-bảng-thanh-toán--payment-statement-calculation)
   - [Bước 7 — Đối soát, phê duyệt & ký điện tử](#bước-7--đối-soát-phê-duyệt--ký-điện-tử--reconciliation-approval--e-sign)
   - [Bước 8 — Phát hành, thông báo & truy vết](#bước-8--phát-hành-thông-báo--truy-vết--issuance-notification--audit)
   - [Bước 9 — Phụ lục hợp đồng (mở rộng của Bước 1)](#bước-9--phụ-lục-hợp-đồng--contract-addendum)
4. [Ma trận truy vết Business Rule → Bước demo](#9-ma-trận-truy-vết-business-rule--bước-demo--traceability-matrix)
5. [Checklist trước giờ G](#10-checklist-trước-giờ-g--pre-flight-checklist)
6. [Phụ lục](#12-phụ-lục--appendix)

---

## Tài khoản demo (password = username)

> Tất cả tài khoản đã seed sẵn qua migration — **password = username** (ví dụ `sales_officer` / `sales_officer`). Copy username làm password, không cần tạo thủ công trong lúc demo.

| Username | Role | Phòng ban | Ghi chú |
|---|---|---|---|
| `admin` | `SYSTEM_ADMIN` | IT | `admin` / `admin` — bootstrap, toàn quyền |
| `sales_officer` | `SALES_OFFICER` | SALES | tạo khách hàng/hợp đồng |
| `sales_manager` | `SALES_MANAGER` | SALES | duyệt bước 1 hợp đồng & bảng giá |
| `legal_reviewer` | `LEGAL_REVIEWER` | LEGAL | duyệt bước Legal |
| `director` | `DIRECTOR` | BOARD | duyệt bước cuối mọi luồng |
| `accountant` | `ACCOUNTANT` | ACCOUNTING | lập & duyệt thanh toán |
| `ops_officer` | `OPS_OFFICER` | OPERATIONS | ghi sản lượng, khóa kỳ |
| `ops_privileged` | `OPS_OFFICER` + `volume:edit_locked` · `statement:cancel_approved` | OPERATIONS | demo sửa sau khóa & hủy đã duyệt |

> **Đã seed sẵn:** `PRC-0001` (EFFECTIVE cho `CTR-2026-0001`, `2026-08-01`→`2027-09-30`, 3 dòng giá) + `2026-08` LOCKED với 3 volumes (`VOL-2026-0001..0003`) + `2026-11` OPEN. Demo vẫn tạo thêm `PRC`/`period`/`volume` mới để show fail-first (PRC-01..06, PAY-01..02).

---

## 7. Chuẩn bị trình diễn / Demo Preparation

### 7.1 Thứ tự đăng nhập khuyến nghị

Demo chạy trên **1 laptop, host local**. Có 2 cách:

**Cách A — Chrome Profiles (khuyến nghị, diễn mượt):**

```
Profile 1: admin              (quản trị)
Profile 2: sales_officer      (tạo hợp đồng)
Profile 3: sales_manager      (duyệt bước 1)
Profile 4: legal_reviewer     (duyệt bước 2)
Profile 5: director           (duyệt bước cuối)
Profile 6: accountant         (thanh toán)
Profile 7: ops_officer        (sản lượng)
```

Mỗi profile đăng nhập 1 tài khoản, để nguyên không logout — cookie `pas_at` 15 phút tự refresh. Xếp cửa sổ cạnh nhau để handoff phê duyệt trực quan.

**Cách B — 1 browser, đăng xuất/đăng nhập liên tục:**

```
Đăng nhập → làm xong bước → Sign out (góc phải header) → đăng nhập tài khoản tiếp theo
```

Nhược điểm: mất hiệu ứng "thông báo đến ngay" đồng thời, nhưng vẫn đúng nghiệp vụ.

### 7.2 Mở sẵn các tab trước giờ G

```
Tab 1: http://localhost:18080/login              (để switch account)
Tab 2: http://localhost:18080/contracts          (Bước 1)
Tab 3: http://localhost:18080/approvals          (Bước 2, 7)
Tab 4: http://localhost:18080/price-lists        (Bước 3+4)
Tab 5: http://localhost:18080/volume-records     (Bước 5)
Tab 6: http://localhost:18080/payment-statements (Bước 6+7)
Tab 7: http://localhost:18080/e-signatures       (Bước 7)
Tab 8: http://localhost:18080/notifications      (Bước 8)
Tab 9: http://localhost:18080/audit-log          (Bước 8, cần admin)
Tab 10: http://localhost:18080/admin/users       (Bước 0)
```

---

## 8. Quy trình demo chi tiết — 8 bước §3 (fail-first per rule) / Detailed Demo Script

> **Quy ước trong mỗi bước:**
> - **Mục tiêu / Goal** — điều cần chứng minh.
> - **Vai trò / Role** — tài khoản đăng nhập.
> - **Đường dẫn / Path** — sidebar hoặc URL.
> - **Fail-first** — các case cố tình làm sai để trigger business rule, mỗi case ghi mã rule.
> - **Happy path** — bước đúng sau khi đã show fail.
> - **Verify** — cách kiểm tra kết quả (badge, status, notification, audit).
> - **Copy blocks** — dữ liệu điền form, copy nguyên văn.
> - `EN:` — dòng dịch cho giảng viên.

---

### **Bước 0 — Quản trị hệ thống / System Administration**


**Mục tiêu:** Chứng minh hệ thống không hard-code workflow/phân quyền — admin cấu hình runtime (`requirement 4.7`, `APR` nền tảng).

**Vai trò:** `admin` (`SYSTEM_ADMIN`)

**Đường dẫn:** `System → Administration` (`/admin/users`, `/admin/roles`, `/admin/workflows`, `/admin/document-types`)

#### 0.1 Tạo người dùng — fail-first

**Fail 1 — Thiếu trường bắt buộc:**

- Nút "New user" → để trống Username → Save
  - Lỗi: "Username is required" (validation FE)
  - Rule: ràng buộc kỹ thuật — input validation

**Fail 2 — Trùng username:**

```text
Username: admin
Password: admin
Email:    duplicate@pas.test
```

- Lỗi: 409 Conflict "Username already exists"
  - Rule: unique constraint (kỹ thuật)

**Happy path — tạo 1 user live (ví dụ sales_officer):**

```text
Username:  sales_officer
Password:  sales_officer
Email:     sales.officer@pas.test
Full name: Nguyen Van Sale
Department: SALES
Role:      SALES_OFFICER
```

→ Save → bảng Users xuất hiện dòng mới, Status `ACTIVE`. Lặp lại cho 5 tài khoản còn lại (đã seed sẵn).

#### 0.2 Phân quyền — Roles & Permissions

- Tab "Roles & Permissions" → chọn SALES_OFFICER
- Bỏ tick customer:write → Save → đăng nhập sales_officer → vào /customers → nút "New Customer" biến mất (mất quyền)
- Tick lại customer:write → Save → F5 → nút xuất hiện lại
  - Rule: Code check permission, không check role (registry §10) — đổi role là đổi bundle, không cần deploy lại.

#### 0.3 Cấu hình workflow — Workflows

- Tab Workflows → chọn CONTRACT → xem 3 bước: Sales review → Legal review → Director sign-off
- Nút "New definition" → tạo bản nháp → thêm/sửa bước → Activate
  - Rule: 4.7 — quy trình không hard-code if/else, lưu như data; running instances giữ version cũ (không nhảy).
- Thử xóa definition đang ACTIVE có instance đang chạy → 412 Precondition Failed
  - Rule: không xóa workflow đang có hồ sơ chạy

#### 0.4 Cấu hình loại hồ sơ — Document Types

- Tab Document Types → chọn CONTRACT → thấy esign_enabled = true, provider = mock
- Edit → đổi Name hoặc tắt/bật e-sign → Save
  - Rule: 4.8 — e-sign là cấu hình theo loại hồ sơ, không phải hard-code.
- Thử đổi code/prefix → không cho (immutable) — chỉ name + e-sign được sửa

**Verify:** Profile page (`/profile`) của mỗi user cho thấy roles/permissions grouped theo module — minh bạch.

---

### **Bước 1 — Tạo khách hàng & hợp đồng / Create Customer & Contract**


**Vai trò:** `sales_officer` (`SALES_OFFICER` — có `customer:write`, `contract:write`)

**Đường dẫn:** `Business Records → Customers` (`/customers`) → `Contracts` (`/contracts`)

#### 1A. Khách hàng — Customers (`/customers`)

**Fail 1 — Thiếu mã số thuế / tên (validation):**

- Nút "+ New Customer" → để trống Name → Save
  - Lỗi: "Name is required"
  - Rule: 4.1 — lưu mã KH, tên, MST, địa chỉ, người đại diện là bắt buộc

**Happy path — tạo khách hàng mới:**

```text
Code:              (auto — hệ thống sinh CUS-XXXX)
Name:              Demo Customer Co
Short name:        DCC
Tax code:          0312345678
Address:           123 Demo Street, District 1, Ho Chi Minh City
Representative:    Nguyen Van Demo — Director
Segment:           Key account
Status:            ACTIVE
Contact 1 (primary):
  Full name:       Nguyen Van Demo
  Email:           demo@dcc.vn
  Phone:           +84 28 3826 9999
  Is primary:      true
```

→ Save → bảng Customers xuất hiện `CUS-0009` (hoặc số tiếp theo), Status `ACTIVE`.

**Tra cứu (4.1):** Click vào `CUS-0001` → tab Contracts/Prices/Statements của khách hàng đó.

**Fail 2 — Khách bị tạm ngưng (liên quan CTR-02):**

- Mở CUS-0006 Mekong Freight Co → Status = SUSPENDED
- Thử tạo hợp đồng cho CUS-0006 → sẽ fail ở bước 1B (CTR-02)
  - Rule: CTR-02 — khách hàng phải hợp lệ (ACTIVE)

#### 1B. Hợp đồng — Contracts (`/contracts`) — fail-first cho CTR-02, CTR-01, CTR-06, CTR-07

**Mở dialog tạo hợp đồng:**

```
Nút "+ New Contract" → form với các trường:
  Customer *, Description, Service group *, Value, Currency, Valid from *, Valid to *,
  Payment term, Billing cycle, VAT rate, Penalty terms, Service clause
```

**Fail 1 — CTR-02: Submit khi thiếu trường bắt buộc (dùng seed CTR-2026-0006 làm ví dụ)**

- Mở CTR-2026-0006 (DRAFT, khách CUS-0006 SUSPENDED, payment_term=null, vat_rate=null)
- Row menu (⋯) → "Submit for approval"
  - Lỗi: 422 / 400 "Contract must have valid customer, valid period and required content"
  - Hoặc nếu tạo mới: để trống Valid from / Valid to / không chọn Customer → Save bị chặn bởi zod schema
        validFrom must not be after validTo
  - Thiếu tệp đính kèm bắt buộc (nếu cấu hình) → cũng 422
  - Rule: CTR-02

**Fail 2 — CTR-02: Thời gian hiệu lực không hợp lệ**

```text
Valid from: 2027-12-01
Valid to:   2026-12-01   (from > to)
```

- Lỗi FE: "validFrom must not be after validTo" (zod refine ở ContractList.tsx)
- Rule: CTR-02 + PRC-02 tương tự

**Fail 3 — CTR-02: Khách hàng không hợp lệ (SUSPENDED)**

```text
Customer: CUS-0006 Mekong Freight Co (SUSPENDED)
Valid from: 2026-10-01
Valid to:   2027-09-30
Payment term: NET30
VAT rate: 8
```

- Submit → Lỗi: "Customer is suspended" hoặc "Customer must be ACTIVE"
- Rule: CTR-02

**Happy path — tạo hợp đồng hợp lệ (copy khối sau):**

```text
Customer:       CUS-0001 Saigon Port Services JSC  (chọn qua CustomerPicker — gõ "SPS" hoặc "CUS-0001")
Description:    Demo stevedoring contract for Sep 2026 teaching
Service group:  STEVEDORING
Value:          1500000000
Currency:       VND
Valid from:     2026-10-01
Valid to:       2027-09-30
Payment term:   NET30
Billing cycle:  MONTHLY (read-only)
VAT rate:       8
Penalty terms:  0.05%/day overdue
Service clause: Contractor shall provide container handling at Cat Lai terminal.
```

→ Save → hợp đồng mới `CTR-2026-0009` (số tiếp theo) với Status `DRAFT`.

**Fail 4 — CTR-01: Chỉ được sửa ở DRAFT / REVISION_REQUESTED**

- Mở CTR-2026-0001 (ACTIVE) → Row menu → không có "Edit" (editable = DRAFT || REVISION_REQUESTED)
- Thử gọi API trực tiếp:
   PUT /api/v1/contracts/{id} với version của ACTIVE
  - 412 / 403 "Contract can only be edited in DRAFT or REVISION_REQUESTED"
  - Rule: CTR-01

**Fail 5 — CTR-06: Hợp đồng ACTIVE không được xóa**

- Mở CTR-2026-0001 (ACTIVE) → Row menu → không có "Cancel contract" nếu thiếu contract:cancel_active
- Đăng nhập sales_officer (không có contract:cancel_active) → nút Cancel không hiện
- Đăng nhập sales_manager (có contract:cancel_active) → nút Cancel hiện nhưng cần confirm + reason
  - Rule: CTR-06 — ACTIVE không xóa, chỉ Cancel/Expired theo quy trình

**Fail 6 — CTR-07: ACTIVE/Approved bị đổi điều khoản quan trọng phải tạo phụ lục**

- Mở CTR-2026-0001 (ACTIVE) → thử Edit valid_to hoặc payment_term
  - Không cho (CTR-01 đã chặn) — hệ thống buộc phải tạo phụ lục
- Row menu → "Create addendum" / "Renew contract" → sang /addenda?contractId=...
  - Rule: CTR-07

**Verify sau Bước 1:** Bảng Contracts có `CTR-2026-0009` DRAFT mới; `CTR-2026-0006` vẫn DRAFT incomplete; `CTR-2026-0001` ACTIVE.

---

### **Bước 2 — Phê duyệt hợp đồng / Contract Approval**


**Vai trò luân phiên:** `sales_officer` (submit) → `sales_manager` → `legal_reviewer` → `director`

**Đường dẫn:** `Workflow → Approvals` (`/approvals?tab=ASSIGNED`) + chi tiết hợp đồng (`/contracts?id=...`)

#### 2.1 Submit — DRAFT → SUBMITTED → UNDER_REVIEW

**Fail 1 — CTR-03: Không được chuyển trực tiếp Draft → Approved**

- Mở CTR-2026-0007 (DRAFT) → Row menu → chỉ có "Submit for approval", không có "Approve"
- Thử gọi POST /workflow-steps/.../actions với DRAFT → 412 "Contract must go through approval"
  - Rule: CTR-03

**Happy path — Submit:**

- Đăng nhập sales_officer → /contracts → tìm CTR-2026-0009 (DRAFT mới tạo)
- Row menu (⋯) → "Submit for approval" → confirm
  - Status: DRAFT → SUBMITTED (ngay) → UNDER_REVIEW (sau khi workflow.instance_started event về, ~1–3s)
  - Verify: Mở /contracts?id=<id> → tab Approval History / ProgressCard hiện "Submitted — workflow initialization pending" rồi chuyển "Under Review"

**Ràng buộc kỹ thuật — Double submit (Idempotency):**

- Bấm "Submit for approval" 2 lần liên tiếp (double-click)
  - Lần 2 trả về instance đã tồn tại, không tạo workflow thứ 2
  - Rule: .5 Double submit — Idempotency-Key / unique constraint, outbox pattern
  - EN: At-least-once relay, dedup by event_id

#### 2.2 Phê duyệt theo bước — fail-first cho APR-01, APR-02, APR-03, APR-04

**Fail 2 — APR-01: Chỉ assignee của bước hiện tại được duyệt**

- Đăng nhập sales_officer (SALES_OFFICER — không phải assignee bước 1, bước 1 cần SALES_MANAGER)
- Vào /approvals → tab ASSIGNED → không thấy task của CTR-2026-0009
- Thử gọi POST /workflow-steps/{stepId}/actions với sales_officer
  - 403 / 412 "Only assignee can act on this step" (workflow kiểm tra step_assignee, không chỉ role)
  - Rule: APR-01 — kiểm tra role là chưa đủ, phải là assignee contextual

**Fail 3 — APR-02: Không nhảy bước**

- Đăng nhập director (DIRECTOR — assignee bước 3) khi hồ sơ đang ở bước 1 (SALES_MANAGER)
- Thử approve → 412 "Step is not active" / "Cannot skip steps"
  - Rule: APR-02

**Fail 4 — APR-03: Reject / Request revision phải có comment**

- Đăng nhập sales_manager → /approvals → ASSIGNED → mở CTR-2026-0009
- Chọn "Reject" → để trống comment → Save
  - Lỗi: "Comment is required when rejecting" / 400 validation
  - Rule: APR-03

**Happy path — duyệt 3 bước:**

- **Bước 1 —** Sales review (SALES_MANAGER):
  - Đăng nhập: sales_manager / sales_manager
  - Vào: /approvals → tab "Assigned to me" → click CTR-2026-0009
  - Hành động: Approve → Comment (optional): "Sales review OK — terms verified"
  - Status step 1: APPROVED, chuyển sang bước 2

- **Bước 2 —** Legal review (LEGAL_REVIEWER):
  - Đăng nhập: legal_reviewer / legal_reviewer
  - Vào: /approvals → ASSIGNED → mở CTR-2026-0009
  - Hành động: Approve → Comment: "Legal review passed — clauses compliant"
  - Chuyển sang bước 3

- **Bước 3 —** Director sign-off (DIRECTOR):
  - Đăng nhập: director / director
  - Vào: /approvals → ASSIGNED → mở CTR-2026-0009
  - Hành động: Approve → Comment: "Approved — proceed to pricing"
  - Workflow completed → Contract: UNDER_REVIEW → APPROVED
  - Notification: event workflow.completed → bell của sales_officer + director
  - Rule: APR-05 — bước cuối approve → APPROVED + event gửi ký (nếu cấu hình)

**Fail 5 — APR-04: Reject kết thúc ở REJECTED / REVISION_REQUESTED**

Demo phụ (dùng CTR-2026-0008 DRAFT khác):
- Submit CTR-2026-0008 → UNDER_REVIEW
- sales_manager → Reject → Comment: "Missing penalty terms" → Confirm
  - Status: REJECTED
- Thử Edit trực tiếp REJECTED → không cho (CTR-01)
- Row menu → "Revise" (CTR-04) → Status: REJECTED → DRAFT → sửa lại → Submit lại
  - Rule: CTR-04 + APR-04 — Rejected không tự sửa và submit lại, phải Revise

**Fail 6 — Race condition (kỹ thuật, nói miệng + show code nếu hỏi):**

Giải thích: 2 request approve cùng lúc vào 1 bước → 1 thành công, 1 bị 409 ABORTED (optimistic locking version)
  - Rule: .5 Race condition — transaction + row-level / optimistic locking
  - Không cần demo live — đã có test, chỉ cần mention khi giảng viên hỏi.

**Verify sau Bước 2:**

- /contracts?id=<new_id> → Status = APPROVED (chưa ACTIVE vì valid_from = 2026-10-01, hôm nay 2026-09-04)
  - Rule: CTR-05 — APPROVED chỉ chuyển ACTIVE khi đến ngày hiệu lực (scheduler D14d, chạy hàng ngày)
- /contracts → CTR-2026-0004 cũng APPROVED với valid_from 2026-11-01 — minh họa tương tự
- /notifications (bell) → có thông báo "Contract CTR-2026-0009 approved" (category APPROVAL)
- /audit-log (admin) → filter Entity = CONTRACT, Entity No = CTR-2026-0009 → thấy các dòng SUBMITTED, UNDER_REVIEW, APPROVED với actor, before/after, note

---

### **Bước 3+4 — Bảng giá & hiệu lực / Price Lists & Effectiveness**


**Vai trò:** `sales_officer` hoặc `sales_manager` (có `pricelist:write`)

**Đường dẫn:** `Business Records → Price Lists` (`/price-lists`)

#### 3.1 Tạo bảng giá — fail-first cho PRC-01, PRC-02

**Mở dialog:**

```
Nút "New price list" → form:
  Applies to: [One contract | One customer and service group | All services for one customer | All customers in one service group]
  Contract / Customer / Service group (tuỳ scope)
  Note (optional)
```

**Fail 1 — PRC-01: Phải gắn với phạm vi rõ ràng**

- Chọn "One contract" → không chọn contract → nút "Create price list" disabled (missingScope)
- Thử gọi API: POST /api/v1/price-lists với {} → 400 "Scope is required — at least one of customerId/contractId/serviceGroup"
  - Rule: PRC-01

**Fail 2 — PRC-02: Ngày không hợp lệ (valid_from > valid_to) — ở bước tạo version**

- Tạo price list thành công (chọn scope hợp lệ) → vào PriceListDetail → "New version"
- Điền:
   Valid from: 2027-01-01
   Valid to:   2026-01-01
  - Lỗi: "validFrom must not be after validTo" (chk_plv_dates)
  - Rule: PRC-02

**Happy path — tạo price list cho hợp đồng demo:**

```text
Applies to: One contract
Contract:   CTR-2026-0009 (vừa tạo ở Bước 1 — gõ "CTR-2026-0009" trong ContractPicker, chỉ hiện APPROVED/ACTIVE)
Note:       Demo price list for STEVEDORING — Sep 2026 teaching
```

→ Save → Price list mới `PRC-2026-0001` (số tiếp theo) với 0 version.

**Tạo version đầu tiên (DRAFT):**

```text
Valid from: 2026-10-01
Valid to:   2027-09-30
Price lines (chọn từ catalog 6 items):
  LIFT_ON_OFF      —  1 200 000 VND / TEU
  STORAGE_OVERTIME —    150 000 VND / day
  LASHING          —    800 000 VND / TEU
  (có thể thêm REEFER_MONITOR, DOC_HANDLING, WEIGHING_VGM tuỳ nhu cầu)
```

→ Save → Version 1 Status `DRAFT`.

#### 3.2 Submit & phê duyệt — fail-first cho PRC-03, PRC-04, PRC-05, PRC-06

**Fail 3 — PRC-03: Hai bảng giá Effective chồng thời gian cùng scope**

- Tạo price list thứ 2 cùng scope (cùng CTR-2026-0009) — nếu UI cho, hoặc tạo version thứ 2 overlapping
- Version 1: 2026-10-01 → 2027-09-30, Status APPROVED/EFFECTIVE
- Version 2: 2026-12-01 → 2027-03-31, thử Approve
  - Lỗi DB: excl_plv_overlap — "Price list versions overlap for same scope"
  - Rule: PRC-03 — không cho 2 Effective chồng lấn cùng đối tượng + cùng loại dịch vụ

**Happy path — Submit & duyệt version:**

- Version 1 DRAFT → "Submit for approval" → Status SUBMITTED ("Under Review")
- Đăng nhập sales_manager → /approvals → ASSIGNED → Price list PRC-2026-0001 v1 → Approve (Commercial approval)
- Đăng nhập director → /approvals → ASSIGNED → Approve (Director sign-off)
  - Status: APPROVED → (scheduler) EFFECTIVE khi đến valid_from (2026-10-01)
  - Rule: Sơ đồ trạng thái Bảng giá — DRAFT → SUBMITTED → APPROVED → EFFECTIVE

**Fail 4 — PRC-04: Version mới phải đánh dấu version cũ Superseded**

- Tạo version 2 cho cùng price list:
   Valid from: 2027-04-01
   Valid to:   2027-09-30
   (overlaps với v1 2026-10-01→2027-09-30)
- Submit + Approve v2
  - Hệ thống tự động: v1.valid_to = v2.valid_from - 1 day (2027-03-31), v1 Status → SUPERSEDED
  - Trong cùng transaction (PRC-04), nếu không truncate sẽ bị PRC-03 reject
  - Rule: PRC-04

**Fail 5 — PRC-05: Đã dùng để sinh thanh toán không được sửa trực tiếp**

- Sau khi đã có statement CALCULATED từ price list này (Bước 6), thử Edit price line của version EFFECTIVE
  - Nút Edit disabled hoặc API trả 412 "Price list already used for billing — create new version"
  - Phải tạo version mới (PRC-05) thay vì sửa trực tiếp
  - Rule: PRC-05

**Fail 6 — PRC-06: Rejected có thể sửa và submit lại**

- Tạo version 3 → Submit → sales_manager Reject với comment "Price too high"
  - Status REJECTED
- Nút "Revise" / Edit lại → sửa unit_price → Save → Status DRAFT → Submit lại được
  - Rule: PRC-06

**Verify:**

```
- /price-lists → PRC-2026-0001 → tab Versions → thấy v1 EFFECTIVE (hoặc APPROVED nếu chưa đến valid_from), v2 SUPERSEDED demo
- /audit-log → filter PRC-2026-0001 → thấy các transition DRAFT→SUBMITTED→APPROVED
```

---

### **Bước 5 — Sản lượng & khóa kỳ / Volume & Period Lock**


**Vai trò:** `ops_officer` (`OPS_OFFICER` — có `volume:write`, `volume:lock_period`)

**Đường dẫn:** `Business Records → Volume Records` (`/volume-records?tab=periods` và `?tab=records`)

#### 5.1 Kỳ — Periods (`tab=periods`)

**Fail 1 — Kỳ đã tồn tại / format sai:**

- Nút "New period" → nhập Period code: 2026-13 → Lỗi: "Invalid period code" (chk_period_code_format ~ YYYY-MM)
- Nhập 2026-09 đã tồn tại → 409 "Period already exists"
  - Rule: period_code unique, format YYYY-MM

**Happy path — tạo kỳ:**

```text
Period code: 2026-09
Start date:  2026-09-01  (auto từ code)
End date:    2026-09-30  (auto)
Status:      OPEN (mặc định)
```

→ Save → bảng Periods xuất hiện `2026-09` OPEN.

**Tạo thêm kỳ cho demo thanh toán:**

```text
Period code: 2026-10
→ Cũng OPEN
```

#### 5.2 Sản lượng — Volume Records (`tab=records`)

**Fail 2 — Service code không tồn tại (D7 snapshot):**

```text
Contract:     CTR-2026-0001 (ACTIVE)
Period:       2026-09
Service code: INVALID_CODE
Quantity:     10
```

- Lỗi: 404 "Service item not found" (operations gọi PricingInternal.GetServiceItem → NOT_FOUND)
  - Rule: D7 — validate & snapshot tại entry time, fail ngay thay vì để billing mới báo "no suitable price"

**Fail 3 — Contract không ACTIVE / không tồn tại:**

```text
Contract: CTR-2026-0006 (DRAFT) hoặc CTR-2025-0003 (EXPIRED)
Period:   2026-09
```

- Lỗi: 412 "Contract must be ACTIVE" hoặc 404
  - Rule: PAY-01 liên quan — hợp đồng phải còn hiệu lực

**Happy path — ghi nhận sản lượng (copy khối sau, tạo 2–3 dòng):**

```text
Record 1:
  Contract:     CTR-2026-0001  (chọn qua ContractPicker — gõ "CTR-2026-0001")
  Period:       2026-09
  Service code: LIFT_ON_OFF
  Service name: (auto snapshot — Container lift on/off)
  Unit:         TEU (auto)
  Quantity:     120
  Note:         Demo volume Sep 2026 — vessel ABC

Record 2:
  Contract:     CTR-2026-0001
  Period:       2026-09
  Service code: STORAGE_OVERTIME
  Quantity:     45
  Note:         Overtime storage Sep

Record 3 (optional):
  Contract:     CTR-2026-0001
  Period:       2026-09
  Service code: LASHING
  Quantity:     80
```

→ Save → bảng Volume Records xuất hiện 3 dòng với `VOL-2026-XXXX` auto.

**Fail 4 — Sửa trước khi khóa (cho phép):**

```
1. Mở 1 volume record vừa tạo → Edit → đổi Quantity 120 → 130 → Save → OK
   → Trước khi LOCKED, volume:write cho phép điều chỉnh (4.5)
```

#### 5.3 Khóa kỳ — Lock period

**Fail 5 — Không có quyền khóa:**

- Đăng nhập sales_officer (không có volume:lock_period) → vào Periods tab → nút "Lock period" không hiện / 403
  - Rule: volume:lock_period là quyền riêng, chỉ OPS_OFFICER có

**Happy path — khóa kỳ:**

- Đăng nhập ops_officer → /volume-records?tab=periods → tìm 2026-09 OPEN
- Nút "Lock period" → confirm
  - Status: OPEN → LOCKED (không có unlock)
  - event operations.period_locked → notification tới ACCOUNTANT (recipient_role)

**Fail 6 — Sửa sau khi khóa (4.5):**

- Vẫn ops_officer (không có volume:edit_locked) → mở volume record thuộc 2026-09 LOCKED → Edit
  - Lỗi: 403 "Period is locked — editing requires volume:edit_locked"
  - Rule: 4.5 — sau khi khóa, không được sửa nếu không có quyền đặc biệt
- Đăng nhập ops_privileged (có volume:edit_locked) → Edit lại → Save → OK nhưng bị audit-logged
  - Mở /audit-log → filter volume record → thấy dòng edit với actor = ops_privileged, before/after quantity
  - Rule: volume:edit_locked — mọi use đều traced

**Fail 7 — Khóa rồi không mở lại:**

- Thử tìm nút "Unlock" → không có (OPERATION_PERIOD: OPEN → LOCKED, no unlock)
  - Rule: thiết kế — LOCKED là irreversible, là confirmation duy nhất (volume rows không có status riêng)

**Verify:**

```
- Periods tab: 2026-09 = LOCKED, 2026-10 = OPEN
- Volume Records: các dòng 2026-09 không còn Edit (với ops_officer thường)
- Notifications: accountant nhận "Period 2026-09 locked by Do Thi Ops"
```

---

### **Bước 6 — Lập bảng thanh toán / Payment Statement Calculation**


**Vai trò:** `accountant` (`ACCOUNTANT` — có `statement:write`)

**Đường dẫn:** `Business Records → Payment Statements` (`/payment-statements`)

#### 6.1 Tính tiền — Calculate (DRAFT → CALCULATED)

**Mở dialog:**

```
Nút "+ New Statement" → form:
  Contract * (chỉ ACTIVE) — ContractPicker statuses=["ACTIVE"]
  Period * (month input, e.g. 2026-09)
```

**Fail 1 — PAY-01: Hợp đồng không ACTIVE hoặc không có bảng giá phù hợp**

- Chọn Contract: CTR-2025-0003 (EXPIRED) + Period 2026-09
  - Lỗi: 412 "Contract must be ACTIVE" hoặc 422 "No suitable price list for contract"
  - Rule: PAY-01 — chỉ lập khi hợp đồng còn hiệu lực và có bảng giá phù hợp tại kỳ tính phí

- Chọn CTR-2026-0001 ACTIVE nhưng chưa tạo price list nào (nếu bỏ qua Bước 3+4)
  - Lỗi: 422 "No effective price list at period_end" (billing gọi GetEffectivePriceList với date=period_end)
  - Rule: PAY-01

**Fail 2 — PAY-02: Sản lượng phải thuộc kỳ và đã được xác nhận/khóa**

- Chọn CTR-2026-0001 + Period 2026-10 (OPEN, chưa khóa, hoặc chưa có volume)
  - Lỗi: 422 "Period must be LOCKED" hoặc "No volume records for period"
  - Hoặc chọn Period 2026-08 (không có volume nào) → 422
  - Rule: PAY-02

**Happy path — Calculate (copy khối sau):**

```text
Contract: CTR-2026-0001  (ACTIVE, đã có price list PRC-2026-0001 với LIFT_ON_OFF, STORAGE_OVERTIME)
Period:   2026-09         (LOCKED, đã có 3 volume records ở Bước 5)
```

→ Nút "Calculate" → hệ thống:
  - Gọi `ContractInternal.GetContract` → snapshot `valid_from/to`, `vat_rate`, `payment_term`, `customer_name`
  - Gọi `OperationsInternal.ListVolumes` → lấy volumes của `2026-09`
  - Gọi `PricingInternal.GetEffectivePriceList` với `date = period_end (2026-09-30)` → resolve theo precedence `CONTRACT > CUSTOMER+GROUP > CUSTOMER`, bao gồm cả SUPERSEDED/EXPIRED nếu validity chứa date
  - Tính `amount = unit_price * quantity` per line, `subtotal`, `vat = subtotal * vat_rate`, `total = subtotal + vat`
  - Lưu `statement_no = PMT-2026-XXXX`, mỗi line `source=CALCULATED`, snapshot `unit_price`, `service_name`, `unit` (PAY-03)

→ Status `DRAFT` → `CALCULATED`, redirect tới `/payment-statements?id=PMT-2026-XXXX`.

**Fail 3 — PAY-03: Đơn giá snapshot không bị ảnh hưởng bởi version mới (demo sau khi Calculate)**

- Sau khi Calculate xong, vào /price-lists → PRC-2026-0001 → tạo version mới với LIFT_ON_OFF = 2 000 000 (tăng từ 1 200 000)
- Quay lại /payment-statements?id=PMT-2026-XXXX → total không đổi
  - Rule: PAY-03 — bảng thanh toán lưu đơn giá tại thời điểm tính, không phụ thuộc bảng giá hiện tại

#### 6.2 Đối soát & chỉnh sửa có kiểm soát — CALCULATED → RECONCILED → SUBMITTED

**Fail 4 — PAY-04: Không cho submit nếu tổng âm hoặc thiếu dòng bắt buộc**

- Mở PMT-2026-XXXX (CALCULATED) → thử "Submit for approval" khi total = 0 hoặc không có line nào
  - Lỗi: 422 "Statement must have at least one service line" hoặc "Total amount must not be negative" (CHECK total_amount >= 0)
  - Rule: PAY-04

**Happy path — Reconcile & Edit:**

- Trong PaymentStatementDetail → tab Lines → "Reconcile" hoặc "Add/Edit lines"
- Có thể thêm dòng MANUAL: Service LASHING, Quantity 10, Unit price 800 000 → Amount 8 000 000
- Hoặc sửa dòng CALCULATED → hệ thống đánh dấu source=MANUAL và chuyển về DRAFT (nếu edit ở CALCULATED)
- Nút "Reconcile" → Status CALCULATED → RECONCILED
- Nút "Submit for approval" → RECONCILED → SUBMITTED
  - Rule: Sơ đồ trạng thái — DRAFT→CALCULATED→RECONCILED→SUBMITTED

**Ràng buộc kỹ thuật — Audit & Outbox:**

```
Mỗi transition ghi 1 dòng status_history (local, sync) + 1 outbox row (audit.recorded → Kafka pas.audit → audit-service)
→ Không mất event nhờ Outbox Pattern (relay claim → publish acks=all → dedup processed_event)
→ EN: Every status change writes status_history + outbox in one transaction; relay retries until acks=all.
```

---

### **Bước 7 — Đối soát, phê duyệt & ký điện tử / Reconciliation, Approval & E-Sign**


**Vai trò:** `accountant` (Accounting check) → `director` (Director sign-off) → `accountant` (send for signing, publish)

**Đường dẫn:** `/approvals`, `/payment-statements?id=...`, `/e-signatures`

#### 7.1 Phê duyệt thanh toán — SUBMITTED → APPROVED

**Fail 1 — APR-01/02/03 tương tự Bước 2 (áp dụng cho PAYMENT_STATEMENT):**

```
1. Đăng nhập sales_officer (không có approval:act cho statement) → không thấy task
2. Đăng nhập accountant nhưng thử approve bước 2 (Director) khi đang ở bước 1 → 412
3. Reject không comment → 400
   → Rules: APR-01, APR-02, APR-03
```

**Happy path — duyệt 2 bước:**

- **Bước 1 —** Accounting check (ACCOUNTANT):
  - Đăng nhập: accountant / accountant
  - Vào: /approvals → ASSIGNED → PMT-2026-XXXX → Approve → Comment: "Reconciled — volumes match"
  - Chuyển bước 2

- **Bước 2 —** Director sign-off (DIRECTOR):
  - Đăng nhập: director / director
  - Vào: /approvals → ASSIGNED → PMT-2026-XXXX → Approve → Comment: "Approved for signing"
  - Workflow completed → Statement: SUBMITTED → APPROVED
  - Rule: APR-05 — bước cuối approve → APPROVED + event

**Fail 2 — PAY-05: Sau Approved/Signed không được sửa trực tiếp**

- Mở PMT-2026-XXXX (APPROVED) → thử Edit lines → nút Edit không hiện hoặc API 412 "Statement cannot be edited in APPROVED"
- Thử tạo hồ sơ điều chỉnh: trong PaymentStatementsPage → Row menu → "Create adjustment" (nếu có) hoặc hủy và tạo mới
  - Rule: PAY-05 — nếu sai phải tạo hồ sơ điều chỉnh hoặc hủy theo quy trình (cần statement:cancel_approved)

**Fail 3 — PAY-06: Chỉ gửi ký sau khi Approved nội bộ**

- Mở statement RECONCILED hoặc SUBMITTED → Row menu → không có "Send for signing" (chỉ hiện khi APPROVED)
- Thử gọi API POST /api/v1/payment-statements/{no}/send-esign khi RECONCILED → 412 "Statement must be APPROVED for signing"
  - Rule: PAY-06

#### 7.2 Gửi ký điện tử — APPROVED → SIGNING → SIGNED → ISSUED

**Happy path — Send for signing:**

- Đăng nhập accountant → /payment-statements?id=PMT-2026-XXXX (APPROVED)
- Row menu (⋯) → "Send for signing" (cần esign:send) → confirm
  - Status: APPROVED → SIGNING
  - Hệ thống: billing ghi outbox esign.session_requested (idempotency_key permanent) → relay gọi EsignInternal.CreateSigningSession → esign-service tạo session PENDING_SEND → gửi tới esign-mock-provider → callback
  - Rule: APR-06 — bất đồng bộ qua callback/webhook

**Theo dõi ký — E-Signatures (`/e-signatures`):**

- Vào /e-signatures → filter Status: SIGNING → thấy session SIG-XXXX cho PMT-2026-XXXX
- Click vào session → SigningSessionDetail: PENDING_SEND → SIGNING (đã gửi tới provider, đang chờ callback)
- Đợi ~5–10s (mock provider delay) → tự chuyển SIGNING → SIGNED (callback POST /callbacks/esign với result=SIGNED)
  - Rule: APR-06 — hệ thống cập nhật trạng thái ký tương ứng qua webhook
- Quay lại /payment-statements?id=PMT-2026-XXXX → Status SIGNING → SIGNED (nhờ esign.session_completed event)

**Fail 4 — PAY-07: Ký thất bại / hủy phản ánh rõ**

Demo fail (optional, nếu mock provider cho phép):
- Trong /e-signatures → session SIGNING → "Cancel request" (cần esign:cancel) → confirm
  - Session: SIGNING → CANCELLED → Statement: SIGNING → REVISION (PAY-07)
  - Hoặc provider callback FAILED → cũng REVISION
  - Người dùng biết cần xử lý lại (sửa và gửi lại)
  - Rule: PAY-07 + 4.8 — trạng thái ký: PENDING_SEND/SIGNING/SIGNED/FAILED/CANCELLED

**Fail 5 — APR-07: Service lỗi không hỏng dữ liệu đã duyệt (nói miệng):**

Giải thích: Nếu esign-service hoặc notification-service tạm down khi đang APPROVED→SIGNING,
  - outbox relay retry với backoff, dữ liệu APPROVED không mất, session ở PENDING_SEND chờ xử lý.
  - Rule: APR-07 — nghiệp vụ chính không hỏng, cần retry / trạng thái chờ xử lý
  - Đã có processed_event dedup + DLT topics pas.events.DLT / pas.audit.DLT

**Happy path — Publish → ISSUED:**

- Mở PMT-2026-XXXX (SIGNED) → Row menu → "Publish" (hoặc "Issue")
  - Status: SIGNED → ISSUED, tính due_date = f(payment_term), issued_at = now()
  - Đây là trạng thái cuối — hồ sơ được phát hành và lưu trữ (Bước 8)

**Verify sau Bước 7:**

```
- /payment-statements?id=PMT-2026-XXXX → ISSUED, due_date hiển thị, history timeline có đủ APPROVED→SIGNING→SIGNED→ISSUED
- /e-signatures?id=SIG-XXXX → SIGNED, callback log hiển thị
- /notifications → accountant + director nhận "Statement PMT-2026-XXXX signed"
```

---

### **Bước 8 — Phát hành, thông báo & truy vết / Issuance, Notification & Audit**


**Vai trò:** mọi role (notification) + `admin` (audit)

#### 8.1 Thông báo — Notifications (`/notifications`, bell icon)

**Mục tiêu:** Chứng minh Notification Service nhận event bất đồng bộ từ các service khác (4.9).

**Fail-first — chưa đọc vs đã đọc:**

- Đăng nhập director → bell icon hiện badge số unread (ví dụ "3")
- Vào /notifications → tab "Unread" → thấy các thông báo:
- "Contract CTR-2026-0009 requires your approval" (APPROVAL)
- "Statement PMT-2026-XXXX approved" (APPROVAL)
- "Statement PMT-2026-XXXX signed" (ESIGN)
- "Period 2026-09 locked" (SYSTEM — nếu là accountant)
- Thử đánh dấu đã đọc 1 cái → badge giảm 1
- Nút "Mark all as read" → badge về 0
  - Rule: 4.9 — thông báo khi cần xử lý / bị từ chối / được duyệt / ký hoàn tất / sắp hết hạn

**Thông báo sắp hết hạn (EXPIRY):**

```
- Scheduler contract/pricing chạy hàng ngày → event document.expiring → notification tới owner_user_id
- Demo: hợp đồng CTR-2026-0001 hết hạn 2027-02-28 — nếu hôm nay gần ngày đó sẽ thấy EXPIRY notification
- Nếu không có, giải thích: "Scheduler sẽ phát EXPIRY khi còn N ngày, hiện tại chưa tới hạn nên chưa có"
```

#### 8.2 Nhật ký & truy vết — Audit Log (`/audit-log`, cần `audit:view_all`)

**Vai trò:** `admin`

**Đường dẫn:** `System → Audit Log` (`/audit-log`)

**Fail-first — không có quyền:**

- Đăng nhập sales_officer → sidebar không hiện "Audit Log" (permission audit:view_all ẩn)
- Thử GET /api/v1/audit-records trực tiếp → 403 PERMISSION_DENIED
  - Rule: phân quyền — Audit Log chỉ admin / người được grant audit:view_all

**Happy path — tra cứu:**

- Đăng nhập admin → /audit-log
- Filter:
   Service: contract
   Entity type: CONTRACT
   Entity No: CTR-2026-0009  (hợp đồng vừa tạo)
   Action: (All)
  - Thấy các dòng: create, submit_for_approval, approve_step (sales_manager), approve_step (legal), approve_step (director), với before_status/after_status, actor, occurred_at, changes jsonb
- Click 1 dòng → dialog diff hiển thị before/after
- Thử filter theo:
- Service: billing, Entity No: PMT-2026-XXXX → thấy calculate, reconcile, submit, approve, send_esign, publish
- Service: operations, Entity: period 2026-09 → thấy lock_period
- Service: esign, Entity: SIG-XXXX → thấy session status changes
  - Rule: 4.10 — ghi ai, khi nào, hành động, trạng thái trước/sau, ghi chú; tra cứu theo từng hợp đồng/bảng giá/thanh toán/phiên ký

**Ràng buộc kỹ thuật — Audit không phụ thuộc dữ liệu hiện tại:**

Giải thích: Audit lưu snapshot changes (before/after) trong payload audit.recorded, không join với bảng hiện tại.
  - Dù hợp đồng đã bị update sau đó, audit vẫn giữ vết thay đổi quan trọng ban đầu.
  - Rule: .5 Dữ liệu lịch sử — bảng thanh toán lưu đơn giá tại thời điểm tính (PAY-03) là ví dụ tương tự.

**Verify cuối Bước 8:**

```
- Mở ContractDetail của CTR-2026-0009 → tab "History" / "Approval History" → timeline local status_history (DRAFT→SUBMITTED→UNDER_REVIEW→APPROVED) + audit records (eventual) — 2 nguồn, 1 view
- So sánh với /audit-log — cùng event nhưng audit là centralized store, history tab là cross-service read
```

---

### **Bước 9 — Phụ lục hợp đồng / Contract Addendum**


**Vai trò:** `sales_officer` (tạo) → `legal_reviewer` → `director` (duyệt)

**Đường dẫn:** `Business Records → Addenda` (`/addenda`) hoặc từ ContractDetail → "Create addendum"

#### 9.1 Tạo phụ lục — fail-first

**Fail 1 — Không có hợp đồng gốc / hợp đồng chưa ACTIVE/APPROVED:**

- Thử tạo addendum cho CTR-2026-0006 (DRAFT) → có thể bị chặn hoặc cảnh báo "Contract must be APPROVED/ACTIVE"
- Thử không chọn Contract → Save → 400 "Contract is required"
  - Rule: 4.3 — phụ lục gắn với hợp đồng đã tồn tại

**Fail 2 — Thiếu loại thay đổi / effective_from:**

- Mở /addenda → "New Addendum" → không chọn Change type → Save → validation error
- Chọn TERM_EXTENSION nhưng không điền new_valid_to → 400
  - Rule: 4.3 — phụ lục có thể thay đổi đơn giá / thời hạn / điều khoản thanh toán / bổ sung dịch vụ

**Happy path — tạo phụ lục gia hạn (copy khối sau):**

```text
Contract:       CTR-2026-0001  (ACTIVE — Saigon Port Services)
Change type:    TERM_EXTENSION
Description:    Gia hạn thêm 6 tháng do nhu cầu tăng ca
Effective from: 2027-03-01
New valid to:   2027-08-31
```

→ Save → `ADD-2026-0005` Status `DRAFT`.

**Tạo phụ lục bổ sung dịch vụ (demo addendum riêng):**

```text
Contract:       CTR-2026-0001
Change type:    ADDED_SERVICE
Description:    Bổ sung dịch vụ cân VGM
Effective from: 2026-10-01
Service lines:
  WEIGHING_VGM — Weighing (VGM) — TEU — scope: all vessels
```

#### 9.2 Phê duyệt phụ lục — workflow riêng

- Submit ADD-2026-0005 (DRAFT → SUBMITTED → UNDER_REVIEW)
- legal_reviewer → /approvals → ASSIGNED → Approve (Legal review)
- director → Approve (Director sign-off)
  - Status: APPROVED → (scheduler) ACTIVE khi đến effective_from
  - Khi ACTIVE: contract-service áp dụng effect trong cùng transaction:
        TERM_EXTENSION ⇒ contract.valid_to = new_valid_to
        PAYMENT_TERMS  ⇒ contract.payment_term = payment_term_override
        (ADDED_SERVICE, UNIT_PRICE_CHANGE — record-only, không đổi parent)
  - Rule: 4.3 — sau khi phụ lục có hiệu lực, nghiệp vụ sau thời điểm hiệu lực dùng thông tin mới

**Fail 3 — CTR-07: Sửa trực tiếp thay vì tạo phụ lục (đã demo ở Bước 1, nhắc lại):**

- Mở CTR-2026-0001 ACTIVE → Edit → thử đổi valid_to → bị chặn (CTR-01)
- Phải tạo TERM_EXTENSION addendum như trên
  - Rule: CTR-07

**Verify:**

- /addenda → ADD-2026-0005 → Status APPROVED (hoặc ACTIVE nếu đã qua effective_from)
- /contracts?id=CTR-2026-0001 → valid_to đã đổi thành 2027-08-31 (nếu addendum đã ACTIVE)
- /audit-log → filter ADD-2026-0005 → thấy các transition của addendum
- Seed minh họa: ADD-2026-0001 đã ACTIVE và đã áp valid_to cho CTR-2026-0001 (kiểm chứng)

---

## 9. Ma trận truy vết Business Rule → Bước demo / Traceability Matrix

| Mã Rule | Mô tả ngắn | Bước demo | Fail case # |
|---|---|---|---|
| **CTR-01** | Chỉ sửa ở DRAFT / REVISION_REQUESTED | 1B Fail 4 | Edit ACTIVE → 412 |
| **CTR-02** | Submit khi có khách hợp lệ + thời gian hợp lệ + tệp bắt buộc | 1B Fail 1,2,3 | CTR-2026-0006 incomplete |
| **CTR-03** | Không Draft → Approved trực tiếp | 2.1 Fail 1 | Không có nút Approve ở DRAFT |
| **CTR-04** | Rejected phải Revise mới submit lại | 2.2 Fail 5 | REJECTED → Revise → DRAFT |
| **CTR-05** | Approved → Active khi đến ngày hiệu lực | 2.2 Verify | CTR-2026-0004/0009 APPROVED chưa ACTIVE |
| **CTR-06** | ACTIVE không xóa, chỉ Cancel/Expired | 1B Fail 5 | Thiếu/có contract:cancel_active |
| **CTR-07** | Đổi điều khoản quan trọng phải tạo phụ lục | 1B Fail 6 + Bước 9 | Edit ACTIVE bị chặn → tạo addendum |
| **PRC-01** | Bảng giá gắn với phạm vi rõ ràng | 3.1 Fail 1 | Không chọn scope → disabled/400 |
| **PRC-02** | valid_from ≤ valid_to | 3.1 Fail 2 | from > to → 400 |
| **PRC-03** | Không chồng Effective cùng scope | 3.2 Fail 3 | excl_plv_overlap |
| **PRC-04** | Version mới → version cũ Superseded | 3.2 Fail 4 | valid_to truncate |
| **PRC-05** | Đã dùng cho thanh toán không sửa trực tiếp | 3.2 Fail 5 | Edit EFFECTIVE → 412, phải tạo version mới |
| **PRC-06** | Rejected có thể sửa và submit lại | 3.2 Fail 6 | Revise → DRAFT → Submit |
| **PAY-01** | Chỉ lập khi hợp đồng ACTIVE + có bảng giá phù hợp | 6.1 Fail 1 | EXPIRED / no price → 422 |
| **PAY-02** | Sản lượng thuộc kỳ và đã khóa/xác nhận | 6.1 Fail 2 | OPEN period → 422 |
| **PAY-03** | Lưu đơn giá tại thời điểm tính (snapshot) | 6.1 Fail 3 | Đổi giá sau Calculate, total không đổi |
| **PAY-04** | Không submit nếu tổng âm / thiếu dòng | 6.2 Fail 4 | total 0 / no lines → 422 |
| **PAY-05** | Sau Approved/Signed không sửa trực tiếp | 7.1 Fail 2 | Edit APPROVED → 412 |
| **PAY-06** | Chỉ gửi ký sau Approved | 7.1 Fail 3 | RECONCILED → Send → 412 |
| **PAY-07** | Ký thất bại/hủy phản ánh rõ → REVISION | 7.2 Fail 4 | CANCELLED/FAILED → REVISION |
| **APR-01** | Chỉ assignee bước hiện tại được duyệt | 2.2 Fail 2 + 7.1 Fail 1 | 403 contextual |
| **APR-02** | Không nhảy bước / duyệt lại bước đã xong | 2.2 Fail 3 | 412 Step not active |
| **APR-03** | Reject/Revision phải có comment | 2.2 Fail 4 | 400 comment required |
| **APR-04** | Reject → REJECTED/REVISION | 2.2 Fail 5 | REJECTED terminal |
| **APR-05** | Bước cuối approve → APPROVED + event | 2.2 Happy + 7.1 Happy | workflow.completed |
| **APR-06** | Ký bất đồng bộ qua callback | 7.2 Happy | PENDING_SEND→SIGNING→SIGNED |
| **APR-07** | Service lỗi không hỏng dữ liệu đã duyệt (retry) | 7.2 Fail 5 | outbox retry, nói miệng |
| **.5 Double submit** | Bấm Submit 2 lần không tạo 2 workflow | 2.1 Double submit | Idempotency-Key |
| **.5 Race** | 2 approve cùng lúc → 1 ABORTED | 2.2 Fail 6 | optimistic locking |
| **.5 Outbox** | Cập nhật trạng thái + gửi event atomic | 6.2 + 7.2 | outbox relay |
| **.5 Lịch sử** | Thanh toán lưu đơn giá snapshot | 6.1 Fail 3 | PAY-03 |
| **4.5 Volume lock** | Sau khóa không sửa nếu không có volume:edit_locked | 5.3 Fail 6 | 403 → privileged OK |
| **4.9 Notification** | Event → inbox, mark read | 8.1 | bell + tabs |
| **4.10 Audit** | Ghi ai/khi nào/hành động/before-after | 8.2 | /audit-log |
| **4.7 Workflow config** | Không hard-code, cấu hình runtime | 0.3 | admin/workflows |
| **4.8 E-sign** | Trạng thái riêng, không trộn với approval | 7.2 | PENDING_SEND…CANCELLED |

---

## 10. Checklist trước giờ G / Pre-flight Checklist

```
□ make up đã chạy, docker compose ps — tất cả healthy/running
□ http://localhost:18080/login mở được, http://localhost:18080/docs/identity mở được
□ Đã kiểm tra 7 tài khoản demo đăng nhập được (password = username): sales_officer, sales_manager, legal_reviewer, director, accountant, ops_officer, ops_privileged
□ Đã tạo Chrome Profiles (7 profiles) và đăng nhập từng tài khoản, để nguyên không logout
□ Đã mở sẵn 10 tabs (login, contracts, approvals, price-lists, volume-records, payment-statements, e-signatures, notifications, audit-log, admin)
□ Đã kiểm tra seed data: SELECT contract_no, status — thấy CTR-2026-0001 ACTIVE, CTR-2026-0006 DRAFT incomplete; PRC-0001 EFFECTIVE; period 2026-08 LOCKED
□ Đã chuẩn bị file đính kèm mẫu (PDF 1 trang) cho CTR-02 nếu workflow yêu cầu attachment
□ Đã test 1 lần full flow 1B Happy → 2.1 Submit (để chắc submit không lỗi trước khi diễn)
□ Đã chuẩn bị slide/màn chiếu — độ phân giải 1920x1080, font lớn, bật zoom 125% cho DataTable
□ Đã tắt notification hệ điều hành, tắt sleep/lock screen
```

---

## 12. Phụ lục / Appendix

### A. Dữ liệu đã seed & còn lại (nếu muốn demo nhanh hơn)

> `[x]` = đã seed sẵn qua migration trong branch này. `[ ]` = chưa làm, có thể mock thêm ở session sau nếu cần rút ngắn hơn nữa.

- [x] **Price list `PRC-0001`** cho `CTR-2026-0001` với 3 lines (LIFT_ON_OFF 1.2M, STORAGE_OVERTIME 150k, LASHING 800k), version EFFECTIVE `2026-08-01`→`2027-09-30` — dùng cho PAY-01/03 happy path. Demo vẫn tạo thêm `PRC` mới để show PRC-01..06 fail-first.
- [x] **Period `2026-08` LOCKED** + 3 volumes (`VOL-2026-0001..0003`) cho `CTR-2026-0001` + `2026-11` OPEN — dùng cho PAY-02/03. Demo vẫn tạo `2026-09`/`2026-10` mới để show lock & edit_locked.
- [ ] Seed statement `PMT-2026-DEMO` CALCULATED sẵn — để vào thẳng Bước 7 (hiện demo Calculate live).
- [ ] Seed 1 signing session FAILED/CANCELLED mẫu — để demo PAY-07 mà không cần chờ provider fail thật.
- [ ] (Optional) Seed thêm 1 hợp đồng APPROVED với `valid_from` = ngày demo + 1 (để minh họa CTR-05 scheduler rõ hơn).

### B. Thuật ngữ / Glossary

| Tiếng Việt | English | Ghi chú |
|---|---|---|
| Khách hàng | Customer | `CUS-XXXX` |
| Hợp đồng | Contract | `CTR-YYYY-XXXX` |
| Phụ lục | Addendum | `ADD-YYYY-XXXX` |
| Bảng giá | Price List | `PRC-YYYY-XXXX` |
| Sản lượng | Volume Record | `VOL-YYYY-XXXX` |
| Bảng thanh toán | Payment Statement | `PMT-YYYY-XXXX` |
| Phiên ký | Signing Session | `SIG-XXXX` |
| Kỳ | Period | `YYYY-MM` (monthly) |
| Trình duyệt | Submit | DRAFT → SUBMITTED |
| Phê duyệt | Approve | step APPROVED |
| Từ chối | Reject | step REJECTED, cần comment |
| Yêu cầu chỉnh sửa | Request Revision | REVISION_REQUESTED |
| Tạm ngưng | Suspended | Customer status |
| Khóa kỳ | Lock period | OPEN → LOCKED, irreversible |

### C. Liên kết nhanh / Quick Links (khi đang diễn)

```
Login:              http://localhost:18080/login
Customers:          http://localhost:18080/customers
Contracts:          http://localhost:18080/contracts
Addenda:            http://localhost:18080/addenda
Price Lists:        http://localhost:18080/price-lists
Volume Records:     http://localhost:18080/volume-records
Payment Statements: http://localhost:18080/payment-statements
Approvals:          http://localhost:18080/approvals
E-Signatures:       http://localhost:18080/e-signatures
Notifications:      http://localhost:18080/notifications
Audit Log:          http://localhost:18080/audit-log
Administration:     http://localhost:18080/admin/users
Profile:            http://localhost:18080/profile
Docs:               http://localhost:18080/docs/identity
                    http://localhost:18080/docs/workflow
                    http://localhost:18080/docs/pricing
                    http://localhost:18080/docs/billing
```

---

*Tài liệu này bám sát `docs/requirement.md` §3–, `docs/design/00-registry.md` §3/§7/§9, và seed migrations `V2__seed_identity.sql` + `V7__seed_demo_users.sql`, `V3__demo_seed.sql`, `V2__seed_service_catalog.sql` + `V7__seed_demo_price_lists.sql`, `V2__seed_workflow.sql`, `V3__seed_demo_periods_volumes.sql`. Mọi mã business rule trong bảng truy vết đều có fail-first case tương ứng trong §8 — giảng viên có thể yêu cầu diễn bất kỳ rule nào bằng cách nhảy tới Fail case ghi trong cột cuối.*
