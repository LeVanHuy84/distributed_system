package com.flashorder.mock.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MockUserServiceTest {
	private MockUserService userService;

	@BeforeEach
	void setUp() {
		userService = new MockUserService();
	}

	@Test
	void validateUser_withValidUserId_shouldReturnTrue() {

		boolean result = userService.validateUser("user-123");

		assertTrue(result);
	}

	@Test
	void validateUser_withNullUserId_shouldReturnFalse() {

		boolean result = userService.validateUser(null);

		assertFalse(result);
	}

	@Test
	void validateUser_withBlankUserId_shouldReturnFalse() {

		boolean result = userService.validateUser("   ");

		assertFalse(result);
	}

	@Test
	void validateUser_withBannedUser_shouldReturnFalse() {

		boolean result = userService.validateUser("banned-user");

		assertFalse(result);
	}
}
