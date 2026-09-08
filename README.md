# Bài 4 (Session 03) — Phân tích sự cố Service Discovery khi scale hệ thống

_Hệ thống FoodX · Eureka Service Registration & Discovery · Bản nộp lại kèm file cấu hình dự án_

## Cấu trúc thư mục bài nộp
```
SS03_Bai04_Eureka_Fix/
├── README.md                          # file này — phân tích + giải thích
├── restaurant-service/
│   └── src/main/resources/application.yml   # cấu hình Eureka Client ĐÃ SỬA
├── order-service/
│   ├── src/main/resources/application.yml   # cấu hình client tra cứu instance mới nhất
│   └── src/main/java/com/foodx/orderservice/config/RestClientConfig.java  # @LoadBalanced
└── eureka-server/
    ├── src/main/resources/application.yml
    └── src/main/java/com/foodx/eurekaserver/EurekaServerApplication.java
```

---

## 1. Vấn đề đặt tên không nhất quán hoa/thường

`spring.application.name = RestaurantService` lệch quy ước với `order-service`, `payment-service` (kebab-case, chữ thường).

**Hậu quả (dù Eureka Client vẫn chạy):**
- Eureka lưu tên viết hoa thành `RESTAURANTSERVICE`, còn service khác là `ORDER-SERVICE`. Khi gọi bằng tên logic phải nhớ đúng `RestaurantService` → dễ gọi nhầm `restaurant-service` → không tìm thấy service.
- Không nhất quán khiến tra cứu qua Eureka Dashboard và trong code dễ nhầm, khó bảo trì khi cả team giả định mọi service đều kebab-case.

**Sửa:** thống nhất `restaurant-service`.

## 2. Lỗi thiếu `/` ở cuối `defaultZone`

Endpoint đăng ký chuẩn của Eureka là `.../eureka/`. Cấu hình cũ `http://eureka-server:8761/eureka` **thiếu dấu `/`** → Eureka Client trỏ sai endpoint → **đăng ký thất bại** → order-service không thấy đủ 4 instance mới scale. Đã sửa thành `http://eureka-server:8761/eureka/` (xem file cấu hình đính kèm).

## 3. Cơ chế heartbeat khi một instance crash đột ngột

- Mỗi instance gửi **heartbeat** định kỳ, mặc định **30 giây/lần** (`lease-renewal-interval-in-seconds = 30`).
- Eureka giữ mỗi instance với thời hạn lease mặc định **90 giây** (`lease-expiration-duration-in-seconds = 90`).
- Nếu `restaurant-service-2` **crash đột ngột** (không kịp deregister), nó ngừng gửi heartbeat. Eureka **không loại ngay** mà chờ hết hạn lease (~90 giây không nhận heartbeat) rồi mới đánh dấu chết và loại khỏi danh sách.
- Tiến trình dọn dẹp (eviction task) chạy định kỳ (~60 giây) nên **thời gian thực tế loại instance chết có thể tới ~90 giây hoặc hơn**.
- **Self-Preservation Mode:** nếu quá nhiều instance mất heartbeat cùng lúc (nghi lỗi mạng), Eureka tạm giữ lại để tránh xóa nhầm hàng loạt.

Hệ quả: trong ~90 giây sau khi instance-2 chết, order-service vẫn có thể nhận IP instance-2 từ cache → cần chịu lỗi phía client (retry + Circuit Breaker).

## 4. order-service nên cấu hình thế nào để luôn có danh sách instance mới nhất

- Là Eureka Client, bật `fetch-registry: true`, có thể giảm `registry-fetch-interval-seconds` để cập nhật danh bạ nhanh hơn (xem `order-service/application.yml`).
- Gọi bằng **tên logic + `@LoadBalanced RestTemplate`** (`http://restaurant-service/...`) thay vì IP cứng → Spring Cloud LoadBalancer phân phối đều cho cả 4 instance, tự loại instance đã bị Eureka gỡ.
- Thêm **retry + Circuit Breaker (Resilience4j)** để nếu lỡ gọi trúng instance vừa chết (trong ~90s) thì thử instance khác.

---

## 5. Tổng kết

| Vấn đề | Nguyên nhân gốc | Sửa |
|--------|-----------------|-----|
| Chỉ gọi được 1 instance | `defaultZone` thiếu `/eureka/` → đăng ký thất bại | Thêm `/` cuối |
| Khó tra cứu tên | `RestaurantService` lệch quy ước | Đổi `restaurant-service` |
| Gọi trúng instance chết | Heartbeat lease ~90s mới loại | Client load-balanced + Circuit Breaker |

Các file cấu hình đã sửa được đính kèm trong cấu trúc thư mục ở đầu tài liệu.
