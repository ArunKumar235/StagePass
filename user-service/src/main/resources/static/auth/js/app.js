/**
 * StagePass Access Control SPA JavaScript
 * Manages State, Validation, REST Operations, JWT Decoding, and Alerts.
 */

// API Base Paths
const API_AUTH = '/auth';
const API_USERS = '/users';

// State Variables
let currentUser = null;

document.addEventListener('DOMContentLoaded', () => {
    initApp();
});

/**
 * Initialize application state, check URL params, and manage SPA routing.
 */
function initApp() {
    // 1. Process URL search parameters (for OAuth2 Redirects)
    const urlParams = new URLSearchParams(window.location.search);
    const tokenParam = urlParams.get('token');
    const errorParam = urlParams.get('error');

    if (tokenParam) {
        // Successful OAuth Login redirect
        localStorage.setItem('stagepass_token', tokenParam);
        
        // Clean URL to prevent token leakage in history
        window.history.replaceState({}, document.title, window.location.pathname);
        
        showToast('Success', 'Authenticated successfully via OAuth!', 'success');
        routeToState();
    } else if (errorParam) {
        // Failed OAuth login redirect
        window.history.replaceState({}, document.title, window.location.pathname);
        showToast('Authentication Failed', decodeURIComponent(errorParam), 'error');
        routeToState();
    } else {
        // Standard SPA loading
        routeToState();
    }
}

/**
 * Switch view depending on user token existence.
 */
function routeToState() {
    const token = localStorage.getItem('stagepass_token');
    const authCard = document.getElementById('auth-card');
    const profileCard = document.getElementById('profile-card');

    if (token) {
        // User logged in - show profile card and hide auth
        authCard.classList.remove('active');
        profileCard.classList.add('active');
        fetchUserProfile(token);
    } else {
        // User logged out - show auth card and hide profile
        profileCard.classList.remove('active');
        authCard.classList.add('active');
        switchTab('login'); // default view
    }
}

/**
 * Switch active tabs (Login vs Register)
 */
function switchTab(tab) {
    const tabLogin = document.getElementById('tab-login');
    const tabRegister = document.getElementById('tab-register');
    const loginPane = document.getElementById('login-pane');
    const registerPane = document.getElementById('register-pane');

    if (tab === 'login') {
        tabLogin.classList.add('active');
        tabRegister.classList.remove('active');
        loginPane.classList.add('active');
        registerPane.classList.remove('active');
    } else {
        tabRegister.classList.add('active');
        tabLogin.classList.remove('active');
        registerPane.classList.add('active');
        loginPane.classList.remove('active');
    }
}

/**
 * Toggle field password visibility (Eye Icon)
 */
function togglePasswordVisibility(fieldId, button) {
    const input = document.getElementById(fieldId);
    const icon = button.querySelector('i');
    
    if (input.type === 'password') {
        input.type = 'text';
        icon.classList.remove('fa-regular', 'fa-eye');
        icon.classList.add('fa-regular', 'fa-eye-slash');
    } else {
        input.type = 'password';
        icon.classList.remove('fa-regular', 'fa-eye-slash');
        icon.classList.add('fa-regular', 'fa-eye');
    }
}

/**
 * Basic Password Strength Checker for Sign-Up Flow
 */
function checkPasswordStrength(password) {
    const strengthBar = document.getElementById('strength-bar');
    const strengthText = document.getElementById('strength-text');
    
    if (!password) {
        strengthBar.style.width = '0%';
        strengthText.textContent = 'Password Strength';
        strengthText.style.color = 'var(--text-muted)';
        return;
    }

    let score = 0;
    if (password.length >= 8) score++;
    if (password.length >= 12) score++;
    if (/[A-Z]/.test(password)) score++;
    if (/[a-z]/.test(password)) score++;
    if (/[0-9]/.test(password)) score++;
    if (/[^A-Za-z0-9]/.test(password)) score++;

    let percent = (score / 6) * 100;
    strengthBar.style.width = `${percent}%`;

    if (score <= 2) {
        strengthBar.style.backgroundColor = 'var(--text-error)';
        strengthText.textContent = 'Weak Password';
        strengthText.style.color = 'var(--text-error)';
    } else if (score <= 4) {
        strengthBar.style.backgroundColor = '#fbaf08';
        strengthText.textContent = 'Moderate Password';
        strengthText.style.color = '#fbaf08';
    } else {
        strengthBar.style.backgroundColor = 'var(--text-success)';
        strengthText.textContent = 'Strong Password';
        strengthText.style.color = 'var(--text-success)';
    }
}

/**
 * Simple forgot password hint
 */
function forgotPassword(event) {
    event.preventDefault();
    showToast('Reset Link', 'Please contact support or sign up a new account in developer environments.', 'info');
}

/**
 * REST Call: Register Account (POST /auth/register)
 */
async function handleRegister(event) {
    event.preventDefault();
    
    const username = document.getElementById('register-username').value.trim();
    const email = document.getElementById('register-email').value.trim();
    const password = document.getElementById('register-password').value;

    const submitBtn = event.target.querySelector('button[type="submit"]');
    setLoadingState(submitBtn, true, 'Creating...');

    try {
        const response = await fetch(`${API_AUTH}/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, username, password })
        });

        if (response.ok) {
            showToast('Account Created', 'Registration successful! You can now sign in.', 'success');
            document.getElementById('register-form').reset();
            checkPasswordStrength(''); // Reset strength bar
            switchTab('login');
        } else {
            const errData = await response.json().catch(() => ({}));
            const message = errData.message || 'Registration failed. Check details.';
            showToast('Register Error', message, 'error');
        }
    } catch (err) {
        showToast('Network Error', 'Unable to reach the user service.', 'error');
        console.error(err);
    } finally {
        setLoadingState(submitBtn, false, 'Create Account');
    }
}

/**
 * REST Call: Login Account (POST /auth/login)
 */
async function handleLogin(event) {
    event.preventDefault();

    const email = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-password').value;

    const submitBtn = event.target.querySelector('button[type="submit"]');
    setLoadingState(submitBtn, true, 'Signing In...');

    try {
        const response = await fetch(`${API_AUTH}/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });

        if (response.ok) {
            const data = await response.json();
            localStorage.setItem('stagepass_token', data.accessToken);
            showToast('Success', 'Access Granted. Welcome back!', 'success');
            document.getElementById('login-form').reset();
            routeToState();
        } else {
            let message = 'Incorrect credentials. Please try again.';
            if (response.status === 401) {
                message = 'Invalid email or password.';
            } else {
                const errData = await response.json().catch(() => ({}));
                message = errData.message || message;
            }
            showToast('Access Denied', message, 'error');
        }
    } catch (err) {
        showToast('Network Error', 'Connection failed. Is the server running?', 'error');
        console.error(err);
    } finally {
        setLoadingState(submitBtn, false, 'Sign In');
    }
}

/**
 * Helper: Decode JWT client-side to read claims
 */
function decodeJwt(token) {
    try {
        const base64Url = token.split('.')[1];
        const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
        const jsonPayload = decodeURIComponent(window.atob(base64).split('').map(function(c) {
            return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
        }).join(''));

        return JSON.parse(jsonPayload);
    } catch (e) {
        console.error('Failed to parse JWT payload', e);
        return null;
    }
}

/**
 * REST Call: Fetch User Profile details (GET /users/me)
 * Emulates Gateway context injection by appending X-User-Id.
 */
async function fetchUserProfile(token) {
    const claims = decodeJwt(token);
    if (!claims) {
        handleLogout();
        return;
    }

    const userId = claims.userId || claims.sub;
    
    try {
        const response = await fetch(`${API_USERS}/me`, {
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${token}`,
                'X-User-Id': userId
            }
        });

        if (response.ok) {
            currentUser = await response.json();
            updateProfileUI(currentUser);
        } else {
            showToast('Session Expired', 'Please sign in again.', 'info');
            handleLogout();
        }
    } catch (err) {
        showToast('Offline Mode', 'Displaying account details from token.', 'info');
        // Render fallback using JWT claims
        currentUser = {
            id: userId,
            email: claims.email,
            username: claims.name || claims.username || 'User',
            role: claims.role || 'USER',
            authProvider: claims.authProvider || 'LOCAL',
            createdAt: null
        };
        updateProfileUI(currentUser);
        console.error(err);
    }
}

/**
 * Update UI Profile components with fresh data
 */
function updateProfileUI(user) {
    // Basic labels
    document.getElementById('profile-username-display').textContent = user.username;
    document.getElementById('profile-email').textContent = user.email;
    document.getElementById('profile-id').textContent = user.id;
    document.getElementById('update-username').value = user.username;

    // Badges & styling
    const providerBadge = document.getElementById('profile-provider-badge');
    const providerText = document.getElementById('profile-provider-text');
    const avatar = document.getElementById('profile-avatar');
    
    // Set role badge
    document.getElementById('profile-role').textContent = user.role || 'USER';

    // Provider settings
    const provider = user.authProvider || 'LOCAL';
    providerBadge.className = `provider-badge ${provider.toLowerCase()}`;
    providerBadge.textContent = provider.toUpperCase();

    if (provider === 'GOOGLE') {
        providerText.textContent = 'Google Connected Account';
        avatar.innerHTML = `<i class="fa-brands fa-google"></i>`;
        avatar.style.background = 'linear-gradient(135deg, #EA4335 0%, #4285F4 100%)';
    } else if (provider === 'GITHUB') {
        providerText.textContent = 'GitHub Linked Account';
        avatar.innerHTML = `<i class="fa-brands fa-github"></i>`;
        avatar.style.background = 'linear-gradient(135deg, #24292e 0%, #1f2328 100%)';
    } else {
        providerText.textContent = 'Email and Secure Password';
        avatar.innerHTML = `<i class="fa-solid fa-user-ninja"></i>`;
        avatar.style.background = 'linear-gradient(135deg, var(--primary) 0%, #3a0ca3 100%)';
    }

    // Created Date parsing
    if (user.createdAt) {
        const date = new Date(user.createdAt);
        document.getElementById('profile-joined').textContent = date.toLocaleDateString(undefined, {
            year: 'numeric', month: 'long', day: 'numeric'
        });
    } else {
        document.getElementById('profile-joined').textContent = 'N/A';
    }
}

/**
 * REST Call: Update Username (PUT /users/me)
 */
async function handleUpdateProfile(event) {
    event.preventDefault();
    
    const token = localStorage.getItem('stagepass_token');
    const newUsername = document.getElementById('update-username').value.trim();

    if (!token || !currentUser) return;
    
    const submitBtn = event.target.querySelector('button[type="submit"]');
    setLoadingState(submitBtn, true, 'Saving...');

    try {
        const response = await fetch(`${API_USERS}/me`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`,
                'X-User-Id': currentUser.id
            },
            body: JSON.stringify({ username: newUsername })
        });

        if (response.ok) {
            const updatedUser = await response.json();
            currentUser = updatedUser;
            updateProfileUI(currentUser);
            showToast('Profile Updated', 'Username successfully saved.', 'success');
        } else {
            const errData = await response.json().catch(() => ({}));
            showToast('Update Error', errData.message || 'Could not update profile.', 'error');
        }
    } catch (err) {
        showToast('Network Error', 'Connection lost.', 'error');
        console.error(err);
    } finally {
        setLoadingState(submitBtn, false, 'Save Changes');
    }
}

/**
 * REST Call: Logout / Destroy Session (POST /auth/logout)
 */
async function handleLogout() {
    const token = localStorage.getItem('stagepass_token');
    
    // Clear local cache immediately to secure the UI state
    localStorage.removeItem('stagepass_token');
    currentUser = null;
    routeToState();
    
    showToast('Signed Out', 'Your session has been securely closed.', 'success');

    // Notify backend if active
    if (token) {
        try {
            await fetch(`${API_AUTH}/logout`, {
                method: 'POST'
            });
        } catch (e) {
            console.warn('Backend logout failed or offline.', e);
        }
    }
}

/**
 * UI State: Add / Remove Spinner loading animation on Buttons
 */
function setLoadingState(button, isLoading, text) {
    const label = button.querySelector('span');
    const icon = button.querySelector('i');

    if (isLoading) {
        button.disabled = true;
        label.textContent = text;
        if (icon) {
            icon.dataset.prevClass = icon.className;
            icon.className = 'fa-solid fa-spinner fa-spin';
        }
    } else {
        button.disabled = false;
        label.textContent = text;
        if (icon && icon.dataset.prevClass) {
            icon.className = icon.dataset.prevClass;
        }
    }
}

/**
 * UI Component: Dynamic Sliding Toasts
 */
function showToast(title, message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;

    // Select suitable icons
    let iconClass = 'fa-solid fa-circle-info';
    if (type === 'success') iconClass = 'fa-solid fa-circle-check';
    if (type === 'error') iconClass = 'fa-solid fa-triangle-exclamation';

    toast.innerHTML = `
        <div class="toast-icon"><i class="${iconClass}"></i></div>
        <div class="toast-content">
            <span class="toast-title">${title}</span>
            <span class="toast-desc">${message}</span>
        </div>
        <button class="toast-close" onclick="closeToast(this)"><i class="fa-solid fa-xmark"></i></button>
        <div class="toast-progress"></div>
    `;

    container.appendChild(toast);

    // Auto dismiss after 4.5 seconds
    setTimeout(() => {
        if (toast.parentNode) {
            toast.classList.add('hide');
            setTimeout(() => {
                toast.remove();
            }, 350);
        }
    }, 4500);
}

function closeToast(button) {
    const toast = button.closest('.toast');
    if (toast) {
        toast.classList.add('hide');
        setTimeout(() => {
            toast.remove();
        }, 350);
    }
}
