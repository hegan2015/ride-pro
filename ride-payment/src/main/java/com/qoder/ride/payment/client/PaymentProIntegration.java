package com.qoder.ride.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.qoder.ride.payment.config.PaymentProperties;
import com.qoder.ride.payment.dataobject.PaymentProAccountDO;
import com.qoder.ride.payment.mapper.PaymentProAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** 用户 → payment-pro 账户映射：懒开户 + 本地持久化 + 余额基线快照；SYS_PLATFORM 映射商户账户（平台=商户）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentProIntegration {

    private final PaymentProClient client;
    private final PaymentProAccountMapper accountMapper;
    private final PaymentProperties properties;

    public boolean isEnabled() {
        return client.isEnabled();
    }

    public String ensureAccount(String userId) {
        if (properties.getPlatformUserId().equals(userId)) {
            PaymentProAccountDO platform = accountMapper.selectByUserId(userId);
            if (platform == null) {
                platform = new PaymentProAccountDO(userId, client.getMerchantMemberId(),
                        client.getMerchantAccountId(), System.currentTimeMillis(),
                        client.balance(client.getMerchantAccountId()));
                try {
                    accountMapper.insert(platform);
                } catch (DuplicateKeyException e) {
                    platform = accountMapper.selectByUserId(userId);
                }
            }
            return platform.getAccountId();
        }
        PaymentProAccountDO row = accountMapper.selectByUserId(userId);
        if (row != null) {
            return row.getAccountId();
        }
        JsonNode opened = client.accountOpen(userId, "USER");
        String accountId = opened.get("accountId").asText();
        row = new PaymentProAccountDO(userId, opened.get("memberId").asText(),
                accountId, System.currentTimeMillis(), client.balance(accountId));
        try {
            accountMapper.insert(row);
            log.info("payment-pro 开户映射 userId={} accountId={}", userId, row.getAccountId());
        } catch (DuplicateKeyException e) {
            row = accountMapper.selectByUserId(userId);
        }
        return row.getAccountId();
    }
}