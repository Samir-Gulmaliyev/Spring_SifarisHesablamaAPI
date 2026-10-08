package az.iktex.coreplatform.simasignapi.service;

import az.iktex.coreplatform.simasignapi.dto.request.OrderItemRequest;
import az.iktex.coreplatform.simasignapi.dto.request.OrderRequest;
import az.iktex.coreplatform.simasignapi.dto.response.OrderItemResponse;
import az.iktex.coreplatform.simasignapi.dto.response.OrderResponse;
import az.iktex.coreplatform.simasignapi.enums.CustomerType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
@Service
public class OrderCalculationService {
    private static final Integer QUANTITY_DISCOUNT_THRESHOLD = 10;
    private static final BigDecimal QUANTITY_DISCOUNT_RATE = new BigDecimal("0.05");
    private static final BigDecimal VIP_DISCOUNT_RATE = new BigDecimal("0.15");
    private static final BigDecimal YENI10_RATE = new BigDecimal("0.10");
    private static final BigDecimal ENDIRIM20_AMOUNT = new BigDecimal("20.00");
    private static final BigDecimal ENDIRIM20_MIN_SUBTOTAL = new BigDecimal("100.00");
    private static final BigDecimal MAX_DISCOUNT_RATE = new BigDecimal("0.30");
    private static final BigDecimal VAT_RATE = new BigDecimal("0.18");
    private static final BigDecimal FREE_DELIVERY_THRESHOLD = new BigDecimal("200.00");
    private static final BigDecimal BAKU_DELIVERY_FEE = new BigDecimal("5.00");
    private static final BigDecimal OTHER_CITY_DELIVERY_FEE = new BigDecimal("10.00");


    public OrderResponse calculate(OrderRequest request) {
        // Burda ilk öncə giriş parametrlərini yoxlayırıq
        if (!isValid(request)) {
            return null;
        }
        // appliedRules üçün List yaradıram. Bu doldurmaq üçün.
        List<String> appliedRules = new ArrayList<>();
        //Geri dönən item-ları bura yığıram.
        List<OrderItemResponse> itemResponses = new ArrayList<>();

        // QAYDA 1 və 2: Sətir məbləği və Say endirimi (calculateLine metodu vasitəsilə yoxlanılır)
        for (OrderItemRequest item : request.getItems()) {
            OrderItemResponse itemResponse = calculateLine(item);
            itemResponses.add(itemResponse);
            // Qayda mətni: Əgər sətirdə say endirimi varsa
            if (itemResponse.getQuantityDiscount().compareTo(BigDecimal.ZERO) > 0) {
                appliedRules.add("Say endirimi 5%: " + itemResponse.getProductName());
            }
        }

        // QAYDA 3: Subtotal (Bütün sətirlərin finalLineTotal cəmi)
        BigDecimal subTotal = BigDecimal.ZERO;
        for (OrderItemResponse orderItemResponse: itemResponses){
            subTotal=subTotal.add(orderItemResponse.getFinalLineTotal()).setScale(2,RoundingMode.HALF_UP);
        }

        // QAYDA 4: Müştəri endirimi (VIP üçün subtotal * 15%, REGULAR üçün 0)
        BigDecimal customerDiscount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (CustomerType.VIP.equals(request.getCustomerType())){
            customerDiscount = subTotal.multiply(VIP_DISCOUNT_RATE)
                    .setScale(2, RoundingMode.HALF_UP);
            appliedRules.add("VIP endirimi 15%");
        }

        // QAYDA 5: Promo kod endirimi (calculatePromoDiscount metodu vasitəsilə)
        BigDecimal promoDiscount = calculatePromoDiscount(request.getPromoCode(), subTotal);
        // Qayda mətnlərinin əlavə edilməsi:
        if ("YENI10".equals(request.getPromoCode())) {
            appliedRules.add("YENI10 promo kodu 10%");
        } else if ("ENDIRIM20".equals(request.getPromoCode())) {
            if (subTotal.compareTo(ENDIRIM20_MIN_SUBTOTAL) >= 0) {
                appliedRules.add("ENDIRIM20 promo kodu 20.00 AZN");
            } else {
                appliedRules.add("ENDIRIM20 tətbiq olunmadı: minimum məbləğ 100.00 AZN");
            }
        }

        // QAYDA 6: Endirim limiti (Maksimum subtotal-ın 30%-i ola bilər)
        BigDecimal totalDiscount = customerDiscount.add(promoDiscount)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal maxAllowedDiscount = subTotal.multiply(MAX_DISCOUNT_RATE)
                .setScale(2, RoundingMode.HALF_UP);

        if (totalDiscount.compareTo(maxAllowedDiscount) > 0) {
            totalDiscount = maxAllowedDiscount;
            appliedRules.add("Endirim limiti tətbiq olundu: maksimum 30%");
        }

        // QAYDA 7: Endirimdən sonra (amountAfterDiscount = subtotal - totalDiscount)
        BigDecimal amountAfterDiscount = subTotal.subtract(totalDiscount)
                .setScale(2, RoundingMode.HALF_UP);

        // QAYDA 8: ƏDV (vat = amountAfterDiscount * 18%)
        BigDecimal vat = amountAfterDiscount.multiply(VAT_RATE)
                .setScale(2, RoundingMode.HALF_UP);

        // QAYDA 9: Çatdırılma (calculateDeliveryFee metodu vasitəsilə)
        BigDecimal deliveryFee = calculateDeliveryFee(amountAfterDiscount, request.getDeliveryCity());
        if (amountAfterDiscount.compareTo(FREE_DELIVERY_THRESHOLD) >= 0) {
            appliedRules.add("Pulsuz çatdırılma");
        }

        // QAYDA 10: Yekun (grandTotal = amountAfterDiscount + vat + deliveryFee)
        BigDecimal grandTotal = amountAfterDiscount.add(vat).add(deliveryFee)
                .setScale(2, RoundingMode.HALF_UP);

        // Bütün nəticələri OrderResponse obyektinə yığıb qaytarırıq
        OrderResponse response = new OrderResponse();
        response.setItems(itemResponses);
        response.setSubtotal(subTotal);
        response.setCustomerDiscount(customerDiscount);
        response.setPromoDiscount(promoDiscount);
        response.setTotalDiscount(totalDiscount);
        response.setAmountAfterDiscount(amountAfterDiscount);
        response.setVat(vat);
        response.setDeliveryFee(deliveryFee);
        response.setGrandTotal(grandTotal);
        response.setAppliedRules(appliedRules);

        return response;
    }

    private boolean isValid(OrderRequest request) {
        if (request == null) {
            return false;
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            System.out.println("XƏTA: Sifarişdə ən azı bir məhsul olmalıdır");
            return false;
        }
        if (request.getCustomerType() == null) {
            System.out.println("XƏTA: Müştəri tipi mütləqdir");
            return false;
        }
        if (request.getDeliveryCity() == null || request.getDeliveryCity().trim().isEmpty()) {
            System.out.println("XƏTA: Çatdırılma şəhəri mütləqdir");
            return false;
        }
        // Items içindəki hər bir məhsulun yoxlaması aparılır. 
        // Yuxarıda field-in null olması yoxlandı. İndi hər məhsul üçün yoxlayıram.
        for (OrderItemRequest item : request.getItems()) {
            if (item.getProductName() == null || item.getProductName().trim().isEmpty()) {
                System.out.println("XƏTA: Məhsulun adı boş ola bilməz");
                return false;
            }
            if (item.getUnitPrice() == null || item.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                System.out.println("XƏTA: Qiymət 0-dan böyük olmalıdır");
                return false;
            }
            if (item.getQuantity() == null || item.getQuantity() < 1) {
                System.out.println("XƏTA: Say 1-dən kiçik ola bilməz");
                return false;
            }
        }

        //Əgər yuxarıda yoxlanılan bütün şərtlərə girməz isə geriyə true qaytaracaq.
        return true;
    }

    private OrderItemResponse calculateLine(OrderItemRequest item) {
        // Burda İntegeri BigDecimala cast etdim.
        BigDecimal quantityBd = BigDecimal.valueOf(item.getQuantity());
        //1. Sətir məbləği: lineTotal = unitPrice × quantity. Hesablanır.
        BigDecimal lineTotal = item.getUnitPrice().multiply(quantityBd).setScale(2, RoundingMode.HALF_UP);
        // BigDecimal tipində quantityDiscount variable yaradıtdım və 0.00 formatına saldım.
        BigDecimal quantityDiscount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        //2. Say endirimi: sətirdə quantity ≥ 10 olarsa, həmin sətrə 5% endirim. finalLineTotal = lineTotal − quantityDiscount.
        if (item.getQuantity() >= QUANTITY_DISCOUNT_THRESHOLD) {
            quantityDiscount = lineTotal.multiply(QUANTITY_DISCOUNT_RATE).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal finalLineTotal = lineTotal.subtract(quantityDiscount).setScale(2, RoundingMode.HALF_UP);
        OrderItemResponse response = new OrderItemResponse();
        response.setProductName(item.getProductName());
        response.setUnitPrice(item.getUnitPrice().setScale(2, RoundingMode.HALF_UP));
        response.setQuantity(item.getQuantity());
        response.setLineTotal(lineTotal);
        response.setQuantityDiscount(quantityDiscount);
        response.setFinalLineTotal(finalLineTotal);
        return response;
    }

    private BigDecimal calculatePromoDiscount(String code, BigDecimal subtotal) {
        //Burda code String olduğu üçün trim() -ilə boşluqları təmizləyirəm.
        String trimmedCode = code.trim();
        // PromoKod null və ya boş gələrsə BigDecimal tipində 0.00 dönəcək.
        if (code == null || trimmedCode.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if ("YENI10".equals(trimmedCode.toUpperCase(Locale.ROOT))) {
            return subtotal.multiply(YENI10_RATE).setScale(2, RoundingMode.HALF_UP);
        } else if ("ENDIRIM20".equals(trimmedCode.toUpperCase(Locale.ROOT))) {
            //Bura biraz fərqlidi ENDIRIM20 promokod o vaxt tətbiq olunacaq ki subtotal 100-dən böyük olacaq.
            if (subtotal.compareTo(ENDIRIM20_MIN_SUBTOTAL) >= 0) {
                return ENDIRIM20_AMOUNT.setScale(2, RoundingMode.HALF_UP);
            } else {
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
        } else {
            // Promokod düz gəlməsə "Promo kod tapılmadı" göndərdik.
            // Hər ehtimala yuxarıda promokodun kiçik hərflə və boşluğunu nəzərə aldım.
            System.out.println("XƏBƏRDARLIQ: Promo kod tapılmadı: " + trimmedCode);
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    private BigDecimal calculateDeliveryFee(BigDecimal amount, String city) {
        //Burada bağımsız 2 if istifadə edirəm çünki əgər birinci if-ə girsə sonrakı if-ə girməyəcək.
        //Məbləğ 200 dən böyük olsa hansı şəhər olmasının heç bir mənası qalmır.
        if (amount.compareTo(FREE_DELIVERY_THRESHOLD) >= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if ("Bakı".equalsIgnoreCase(city) || "Baku".equalsIgnoreCase(city)) {
            return BAKU_DELIVERY_FEE.setScale(2, RoundingMode.HALF_UP);
        } else {
            return OTHER_CITY_DELIVERY_FEE.setScale(2, RoundingMode.HALF_UP);
        }
    }


}
