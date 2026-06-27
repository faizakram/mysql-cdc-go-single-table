package com.migration.platform.connection;

import com.migration.platform.connection.dto.ConstraintDtos.ForeignKeyInfo;
import com.migration.platform.connection.dto.ConstraintDtos.IndexInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintDdlTest {

    @Test
    void buildsUniqueIndexDdlWithSnakeCasedNames() {
        String ddl = ConstraintDdl.indexDdl("public", "Employees",
                new IndexInfo("IX_Employees_Email", true, List.of("Email")));
        assertThat(ddl).isEqualTo(
                "CREATE UNIQUE INDEX IF NOT EXISTS ix_employees_email ON public.employees (email);");
    }

    @Test
    void buildsCompositeNonUniqueIndexDdl() {
        String ddl = ConstraintDdl.indexDdl("public", "Orders",
                new IndexInfo("IX_Orders_Cust_Date", false, List.of("CustomerId", "OrderDate")));
        assertThat(ddl).isEqualTo(
                "CREATE INDEX IF NOT EXISTS ix_orders_cust_date ON public.orders (customer_id, order_date);");
    }

    @Test
    void buildsForeignKeyDdl() {
        String ddl = ConstraintDdl.foreignKeyDdl("public", "Orders",
                new ForeignKeyInfo("FK_Orders_Customer", List.of("CustomerId"), "Customers", List.of("CustomerId")));
        assertThat(ddl).isEqualTo(
                "ALTER TABLE public.orders ADD CONSTRAINT fk_orders_customer "
                        + "FOREIGN KEY (customer_id) REFERENCES public.customers (customer_id);");
    }
}
