package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.payment.LedgerQuery;
import com.qoder.ride.facade.payment.PaymentFacade;
import com.qoder.ride.facade.payment.TopupRequest;
import com.qoder.ride.gateway.web.GatewaySupport;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    @DubboReference
    private PaymentFacade paymentFacade;

    @PostMapping("/topup")
    public ResponseEntity<Object> topup(@RequestBody TopupRequest request) {
        return GatewaySupport.of(paymentFacade.topup(request));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Object> withdraw(@RequestBody TopupRequest request) {
        return GatewaySupport.of(paymentFacade.withdraw(request));
    }

    /** 通用划转（打赏/调度费等）：幂等 requestId，双流水。 */
    @PostMapping("/transfer")
    public ResponseEntity<Object> transfer(@RequestBody com.qoder.ride.facade.payment.TransferRequest request) {
        return GatewaySupport.of(paymentFacade.transfer(request));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<Object> wallet(@PathVariable String userId) {
        return GatewaySupport.of(paymentFacade.wallet(userId));
    }

    /** 提现单列表（受理→处理中→成功/失败）。 */
    @GetMapping("/{userId}/withdraws")
    public ResponseEntity<Object> withdraws(@PathVariable String userId) {
        return GatewaySupport.of(paymentFacade.withdrawOrders(userId));
    }

    /** 本人流水（司乘端钱包页/订单详情抽屉）。 */
    @GetMapping("/{userId}/ledger")
    public ResponseEntity<Object> ledger(@PathVariable String userId,
                                         @RequestParam(required = false) String orderId,
                                         @RequestParam(required = false) String bizType,
                                         @RequestParam(required = false) Long from,
                                         @RequestParam(required = false) Long to,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        LedgerQuery query = new LedgerQuery();
        query.setUserId(userId);
        query.setOrderId(orderId);
        query.setBizType(bizType);
        query.setFrom(from);
        query.setTo(to);
        query.setPage(page);
        query.setSize(size);
        return GatewaySupport.of(paymentFacade.ledger(query));
    }
}