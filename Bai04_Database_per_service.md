# Bài 4 — Phân tích và tái thiết kế tầng dữ liệu theo Database-per-service

_Session 02 — Từ Monolithic đến Microservice · Hệ thống LibraX_

---

## 1. Phân tích các điểm coupling ở tầng dữ liệu

Đoạn code `getBorrowingDetail` vi phạm nghiêm trọng nguyên tắc Database-per-service:

| Vi phạm | Chi tiết |
|---------|----------|
| **Dùng chung 1 database** | Cả 4 service (`book-service`, `member-service`, `borrowing-service`, `notification-service`) cùng trỏ vào `librax_db`. |
| **JOIN xuyên bảng của service khác** | `borrowing-service` chỉ được sở hữu bảng `borrowings`, nhưng câu SQL lại `JOIN books` (thuộc `book-service`) và `JOIN members` (thuộc `member-service`). |
| **Truy cập trực tiếp bảng không sở hữu** | `borrowing-service` đọc thẳng `b.title` và `m.name` — bỏ qua hoàn toàn API của service sở hữu dữ liệu. |

**Rủi ro cụ thể (coupling ẩn ở tầng dữ liệu):**
- **Vỡ khi đổi schema.** Nếu `book-service` đổi tên cột `title` → `book_title`, hoặc `member-service` đổi `name` → `full_name`, thì `borrowing-service` **sập ngay** dù không ai động vào code của nó — đúng kịch bản "coupling ẩn".
- **Không thể tách triển khai thật.** Dù code đã tách 4 service (MSA), tầng dữ liệu vẫn dính chặt → chưa phải microservice đúng nghĩa, mà là "distributed monolith" ở tầng DB.
- **Không thể scale/độc lập DB.** Không thể tối ưu, backup, hay đổi loại database riêng cho từng domain vì tất cả nằm chung.
- **Ranh giới sở hữu dữ liệu bị phá vỡ.** `book-service` không còn là nơi duy nhất kiểm soát dữ liệu sách.

---

## 2. Sơ đồ tách database theo Database-per-service

```
TRƯỚC (coupling):
┌─────────────────────────────────────────────┐
│                  librax_db                    │
│   books   |   members   |  borrowings  | ...  │
└───▲────────────▲──────────────▲───────────────┘
    │            │              │
book-service member-service borrowing-service  ← tất cả trỏ chung + JOIN chéo

SAU (Database-per-service):
book-service ───────► books_db        (bảng books)
member-service ─────► members_db      (bảng members)
borrowing-service ──► borrowings_db   (bảng borrowings, chỉ chứa book_id, member_id)
notification-service► notifications_db (bảng notifications)

Giao tiếp dữ liệu giữa các service: CHỈ qua REST API, không query chéo DB.
```

Nguyên tắc: mỗi service là **chủ sở hữu duy nhất** database của mình. `borrowings_db` chỉ lưu **khóa tham chiếu** `book_id`, `member_id` (không lưu `title`, `name`); muốn có `title`/`name` phải gọi API của service tương ứng.

---

## 3. Viết lại `getBorrowingDetail` (gọi API thay vì JOIN)

```java
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import java.util.logging.Logger;

@Service
public class BorrowingService {

    private static final Logger log = Logger.getLogger(BorrowingService.class.getName());

    private final JdbcTemplate jdbcTemplate;      // chỉ truy cập borrowings_db của chính mình
    private final RestTemplate restTemplate;       // @LoadBalanced để gọi service khác

    public BorrowingService(JdbcTemplate jdbcTemplate, RestTemplate restTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
    }

    public String getBorrowingDetail(Long borrowingId) {
        // 1) Chỉ query bảng borrowings THUỘC SỞ HỮU của chính borrowing-service,
        //    lấy về book_id và member_id (không JOIN sang books/members)
        Borrowing borrowing = jdbcTemplate.queryForObject(
            "SELECT id, book_id, member_id FROM borrowings WHERE id = ?",
            (rs, rowNum) -> new Borrowing(
                rs.getLong("id"), rs.getLong("book_id"), rs.getLong("member_id")),
            borrowingId);

        // 2) Lấy title qua REST API của book-service
        String title = fetchBookTitle(borrowing.bookId());
        // 3) Lấy name qua REST API của member-service
        String memberName = fetchMemberName(borrowing.memberId());

        return "Sách: " + title + " | Độc giả: " + memberName;
    }

    private String fetchBookTitle(Long bookId) {
        try {
            return restTemplate.getForObject(
                "http://book-service/api/books/" + bookId + "/title", String.class);
        } catch (RestClientException ex) {
            log.warning("Không lấy được title bookId=" + bookId + ": " + ex.getMessage());
            return "(không rõ đầu sách)";
        }
    }

    private String fetchMemberName(Long memberId) {
        try {
            return restTemplate.getForObject(
                "http://member-service/api/members/" + memberId + "/name", String.class);
        } catch (RestClientException ex) {
            log.warning("Không lấy được name memberId=" + memberId + ": " + ex.getMessage());
            return "(không rõ độc giả)";
        }
    }

    // Record nội bộ ánh xạ dữ liệu của borrowings_db
    private record Borrowing(Long id, Long bookId, Long memberId) {}
}
```

Điểm mấu chốt: `borrowing-service` chỉ đọc `borrowings_db` của mình, còn `title`/`name` lấy qua API `book-service`/`member-service` — không còn JOIN chéo, đổi schema của service khác không làm nó sập.

---

## 4. Bài toán mới phát sinh sau khi tách & hướng xử lý

**Bài toán 1 — Truy vấn xuyên service chậm hơn (N+1 / độ trễ mạng).**
Trước đây 1 câu JOIN lấy hết; giờ mỗi lần detail phải gọi thêm 2 API qua mạng, chậm hơn và dễ lỗi khi một service không phản hồi.
*Hướng xử lý:* dùng **cache** (Redis) cho dữ liệu ít đổi như tên sách/độc giả; gọi song song 2 API; áp dụng **Circuit Breaker** (Resilience4j) + fallback để không sập dây chuyền; nếu cần đọc nhiều, cân nhắc mẫu **CQRS/read-model** tổng hợp sẵn.

**Bài toán 2 — Giao dịch phân tán (distributed transaction).**
Một nghiệp vụ ghi vào nhiều DB (ví dụ tạo lượt mượn + trừ tồn kho sách + gửi thông báo) không còn nằm trong một transaction ACID duy nhất → dễ mất nhất quán.
*Hướng xử lý:* áp dụng **Saga Pattern** (choreography hoặc orchestrator) với các bước bù trừ (compensating transaction) — sẽ học ở Session 14.
