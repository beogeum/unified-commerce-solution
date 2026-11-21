package com.commerceos.integration;

import com.commerceos.integration.spi.Connector;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 등록된 커넥터 구현. 현재는 모의 커넥터만 있다(DEC-F08). */
@Component
public class ConnectorRegistry {
    private final Map<String, Connector> connectors;

    public ConnectorRegistry(ObjectProvider<Connector> connectors) {
        this.connectors = connectors.orderedStream().collect(Collectors.toMap(Connector::code, Function.identity()));
    }

    public Optional<Connector> find(String code) {
        return Optional.ofNullable(connectors.get(code));
    }

    public List<Connector> all() {
        return connectors.values().stream().sorted((a, b) -> a.code().compareTo(b.code())).toList();
    }
}
