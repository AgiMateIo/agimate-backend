package ru.agimate.connectorsapi.connector.definitions;

import org.springframework.stereotype.Component;
import ru.agimate.connectorsapi.connector.ConnectorDefinition;
import ru.agimate.connectorsapi.connector.ConnectorMethod;
import ru.agimate.connectorsapi.connector.ConnectorMethodCategory;
import ru.agimate.connectorsapi.connector.ConnectorMethodParameter;

import java.util.List;
import java.util.Map;

@Component
public class OzonConnectorDefinition implements ConnectorDefinition {

    @Override
    public String getConnectorCode() {
        return "ozon";
    }

    @Override
    public List<String> getRequiredCredentialFields() {
        return List.of("clientId", "apiKey");
    }

    @Override
    public List<ConnectorMethod> getMethods() {
        return List.of(
                new ConnectorMethod(
                        "getProductList",
                        "Получить список товаров",
                        "Возвращает список товаров продавца с пагинацией",
                        "POST",
                        "/v2/product/list",
                        ConnectorMethodCategory.PRODUCTS,
                        List.of(
                                new ConnectorMethodParameter(
                                        "limit", "Лимит", "Количество товаров на странице",
                                        "integer", false, 100, Map.of("min", 1, "max", 1000)
                                ),
                                new ConnectorMethodParameter(
                                        "last_id", "Last ID", "ID последнего товара для пагинации",
                                        "string", false, null, Map.of()
                                )
                        )
                ),
                new ConnectorMethod(
                        "getProductInfo",
                        "Информация о товаре",
                        "Возвращает детальную информацию о товарах по их идентификаторам",
                        "POST",
                        "/v2/product/info",
                        ConnectorMethodCategory.PRODUCTS,
                        List.of(
                                new ConnectorMethodParameter(
                                        "product_id", "ID товара", "Идентификатор товара в системе Ozon",
                                        "integer", true, null, Map.of()
                                )
                        )
                ),
                new ConnectorMethod(
                        "getFbsPostingList",
                        "Список заказов FBS",
                        "Возвращает список заказов FBS за указанный период",
                        "POST",
                        "/v3/posting/fbs/list",
                        ConnectorMethodCategory.FBS,
                        List.of(
                                new ConnectorMethodParameter(
                                        "since", "С даты", "Начало периода (ISO 8601)",
                                        "datetime", true, null, Map.of()
                                ),
                                new ConnectorMethodParameter(
                                        "to", "По дату", "Конец периода (ISO 8601)",
                                        "datetime", true, null, Map.of()
                                ),
                                new ConnectorMethodParameter(
                                        "limit", "Лимит", "Количество заказов на странице",
                                        "integer", false, 100, Map.of("min", 1, "max", 1000)
                                )
                        )
                ),
                new ConnectorMethod(
                        "getStocks",
                        "Остатки товаров",
                        "Возвращает информацию об остатках товаров на складах",
                        "POST",
                        "/v3/product/info/stocks",
                        ConnectorMethodCategory.STOCKS,
                        List.of(
                                new ConnectorMethodParameter(
                                        "product_id", "ID товаров", "Массив идентификаторов товаров",
                                        "array", true, null, Map.of()
                                )
                        )
                ),
                new ConnectorMethod(
                        "updatePrices",
                        "Обновить цены",
                        "Обновляет цены на товары",
                        "POST",
                        "/v1/product/import/prices",
                        ConnectorMethodCategory.PRICES,
                        List.of(
                                new ConnectorMethodParameter(
                                        "prices", "Цены", "Массив объектов с ценами",
                                        "array", true, null, Map.of()
                                )
                        )
                )
        );
    }
}
