package az.iktex.coreplatform.simasignapi.controller;

import az.iktex.coreplatform.simasignapi.dto.request.OrderRequest;
import az.iktex.coreplatform.simasignapi.dto.response.OrderResponse;
import az.iktex.coreplatform.simasignapi.service.OrderCalculationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sima-sign/api/orders")
public class OrderController {
    public final OrderCalculationService orderCalculationService;

    public OrderController(OrderCalculationService orderCalculationService) {
        this.orderCalculationService = orderCalculationService;
    }

    @PostMapping("/calculate")
    //@ResponseStatus(value = HttpStatus.OK)
    public OrderResponse calculate(@RequestBody OrderRequest request) {
        return orderCalculationService.calculate(request);
    }
}
