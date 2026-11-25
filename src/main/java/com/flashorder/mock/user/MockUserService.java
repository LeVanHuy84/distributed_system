package com.flashorder.mock.user;

import org.springframework.stereotype.Service;

@Service
public class MockUserService implements UserService {

    private static final String BANNED_USER = "banned-user";

    @Override
    public boolean validateUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }

        if (BANNED_USER.equals(userId)) {
            return false;
        }

        return true;
    }
}
