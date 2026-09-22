/**
 * 로그인 상태를 확인한다. 로그인돼 있지 않으면 로그인 화면으로 보내고 false 를 돌려준다.
 *
 * location.href 를 넣어도 자바스크립트는 즉시 멈추지 않는다. 그래서 예전에는
 * 이동하는 동안 호출부가 계속 돌아 API 가 401 을 뱉고, 화면 전환 직전에
 * "불러오지 못했습니다" 경고가 먼저 뜨는 일이 있었다(앱 재시작으로 세션이
 * 날아간 직후에 자주 났다). 호출부가 결과를 보고 멈출 수 있게 값을 돌려준다.
 *
 * 사용: if (!(await authCheck())) return;
 */
async function authCheck() {
  try {
    const res = await fetch('/api/auth/me', {
      method: 'GET',
      credentials: 'include'
    });

    const data = await res.json();

    if (!data.success) {
      location.href = '/login.html';
      return false;
    }

    window.LOGIN_USER = data;
    return true;
  } catch (e) {
    location.href = '/login.html';
    return false;
  }
}

async function logout() {
  try {
    await fetch('/api/auth/logout', {
      method: 'POST',
      credentials: 'include'
    });
  } catch (e) {
  }
  location.href = '/login.html';
}