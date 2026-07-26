package com.releasepilot.application.queries;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.PromotionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionQueryServiceTest {

	@Mock
	private PromotionReadModelPort readModel;

	private PromotionQueryService service;

	private final ApplicationId applicationId = ApplicationId.random();
	private final PromotionId promotionId = PromotionId.random();

	@BeforeEach
	void setUp() {
		service = new PromotionQueryService(readModel);
	}

	@Test
	void getPromotionDetailDelegatesToTheReadModel() {
		PromotionDetail detail = new PromotionDetail(
				promotionId, applicationId, new com.releasepilot.domain.promotion.Version("1.0.0"), null,
				com.releasepilot.domain.promotion.Environment.DEV,
				com.releasepilot.domain.promotion.PromotionStatus.REQUESTED, "alice", null, List.of());
		when(readModel.findPromotionDetail(promotionId)).thenReturn(Optional.of(detail));

		assertThat(service.getPromotionDetail(promotionId)).contains(detail);
	}

	@Test
	void getPromotionDetailPropagatesEmptyWhenNotFound() {
		when(readModel.findPromotionDetail(promotionId)).thenReturn(Optional.empty());

		assertThat(service.getPromotionDetail(promotionId)).isEmpty();
	}

	@Test
	void getApplicationStatusDelegatesToTheReadModel() {
		ApplicationEnvironmentStatus status = new ApplicationEnvironmentStatus(applicationId, List.of());
		when(readModel.findApplicationEnvironmentStatus(applicationId)).thenReturn(status);

		assertThat(service.getApplicationStatus(applicationId)).isEqualTo(status);
	}

	@Test
	void getApplicationPromotionsDelegatesToTheReadModel() {
		PromotionHistoryPage page = new PromotionHistoryPage(List.of(), 0, 20, 0);
		when(readModel.findApplicationPromotions(applicationId, 0, 20)).thenReturn(page);

		assertThat(service.getApplicationPromotions(applicationId, 0, 20)).isEqualTo(page);
		verify(readModel).findApplicationPromotions(applicationId, 0, 20);
	}

	@Test
	void getApplicationPromotionsRejectsANegativePage() {
		assertThatThrownBy(() -> service.getApplicationPromotions(applicationId, -1, 20))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void getApplicationPromotionsRejectsAZeroOrNegativePageSize() {
		assertThatThrownBy(() -> service.getApplicationPromotions(applicationId, 0, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
