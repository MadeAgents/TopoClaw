"""
Reader class for accessing the arXiv data service API.
Provides typed interface with robust error handling and logging.

Also exposes agent :class:`Tool` wrappers: hybrid search, trending, and paper read modes.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from typing import Any, Dict, List, Optional

import requests
from loguru import logger as loguru_logger
from topoclaw.agent.tools.base import Tool

# Configure logger
logger = logging.getLogger(__name__)


class APIError(Exception):
    """Base exception for API errors."""
    pass


class AuthenticationError(APIError):
    """Raised when authentication fails (401, invalid token)."""
    pass


class BadRequestError(APIError):
    """Raised when the request is invalid (400)."""
    pass


class RateLimitError(APIError):
    """Raised when rate limit is exceeded (429)."""
    pass


class NotFoundError(APIError):
    """Raised when requested resource is not found (404)."""
    pass


class ServerError(APIError):
    """Raised when server returns 5xx error."""
    pass


class Reader:
    """Reader for accessing arXiv papers via the data service API.

    Provides comprehensive paper search, metadata retrieval, and content access
    with support for hybrid search (BM25 + Vector) and PMC biomedical literature.

    Attributes:
        token: API token for authentication (optional for free papers)
        base_url: Base URL of the data service
        timeout: Request timeout in seconds (default: 60)
        max_retries: Maximum number of retry attempts (default: 3)
        retry_delay: Initial retry delay in seconds (default: 1)
    """

    def __init__(
        self,
        token: Optional[str] = None,
        base_url: str = "https://data.rag.ac.cn",
        timeout: int = 60,
        max_retries: int = 3,
        retry_delay: float = 1.0,
    ) -> None:
        """
        Initialize the Reader.

        Args:
            token: API token for authentication (optional for free papers)
            base_url: Base URL of the data service (default: https://data.rag.ac.cn)
            timeout: Request timeout in seconds (default: 60)
            max_retries: Maximum number of retry attempts (default: 3)
            retry_delay: Initial retry delay in seconds (default: 1.0)
        """
        if token is not None and str(token).strip():
            self.token = str(token).strip()
        else:
            self.token = (os.getenv("DEEPXIV_API_KEY") or "").strip()
        self.base_url = base_url.rstrip("/")
        self.arxiv_endpoint = f"{self.base_url}/arxiv/"
        self.pmc_endpoint = f"{self.base_url}/pmc/"
        self.websearch_endpoint = f"{self.base_url}/websearch"
        self.semantic_scholar_endpoint = f"{self.base_url}/semantic_scholar"
        self.timeout = timeout
        self.max_retries = max_retries
        self.retry_delay = retry_delay
        logger.debug(
            f"Reader initialized with base_url={self.base_url}, "
            f"token={'***' if self.token else 'None'}"
        )

    def _make_request(
        self,
        url: str,
        params: Optional[Dict[str, Any]] = None,
        retry_count: int = 0,
    ) -> Optional[Dict[str, Any]]:
        """
        Make a request to the API with retry logic and comprehensive error handling.

        Args:
            url: URL to request
            params: Query parameters
            retry_count: Current retry attempt number (internal use)

        Returns:
            Response JSON or None if max retries exceeded

        Raises:
            BadRequestError: Invalid request parameters or malformed IDs (400)
            AuthenticationError: Invalid or expired token (401)
            RateLimitError: Daily limit reached (429)
            NotFoundError: Resource not found (404)
            ServerError: Server error (5xx)
            APIError: Other API errors
        """
        headers: Dict[str, str] = {}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"

        try:
            logger.debug(f"Making request to {url} with params {params}")
            response = requests.get(
                url,
                params=params,
                headers=headers,
                timeout=self.timeout,
            )

            # Handle HTTP errors with appropriate exceptions
            if response.status_code == 400:
                logger.warning(f"Bad request to {url}: {response.text}")
                raise BadRequestError(
                    "Invalid request. Please check your arXiv/PMC ID or command arguments."
                )
            elif response.status_code == 401:
                logger.error("Authentication failed: Invalid or expired token")
                raise AuthenticationError(
                    "Invalid or expired token. Run 'deepxiv config' to set a valid token."
                )
            elif response.status_code == 404:
                logger.warning(f"Resource not found: {url}")
                raise NotFoundError(f"Paper not found. Check your arXiv/PMC ID.")
            elif response.status_code == 429:
                logger.warning("Rate limit exceeded")
                raise RateLimitError(
                    "Daily limit reached. Email tommy@chien.io for higher limits."
                )
            elif response.status_code >= 500:
                logger.error(f"Server error {response.status_code}: {response.text}")
                raise ServerError(f"Server error {response.status_code}")

            response.raise_for_status()
            if not response.content:
                logger.debug(f"Empty response body from {url}")
                return {}
            result = response.json()
            logger.debug(f"Successfully received response from {url}")
            return result

        except APIError:
            raise

        except requests.exceptions.Timeout as e:
            if retry_count < self.max_retries:
                wait_time = self.retry_delay * (2 ** retry_count)
                logger.warning(
                    f"Request timeout (attempt {retry_count + 1}/{self.max_retries}), "
                    f"retrying in {wait_time}s..."
                )
                time.sleep(wait_time)
                return self._make_request(url, params, retry_count + 1)
            else:
                logger.error(f"Request timeout after {self.max_retries} retries")
                raise APIError(
                    f"Request timed out after {self.max_retries} retries. "
                    "Check your internet connection or try again later."
                )

        except requests.exceptions.ConnectionError as e:
            if retry_count < self.max_retries:
                wait_time = self.retry_delay * (2 ** retry_count)
                logger.warning(
                    f"Connection error (attempt {retry_count + 1}/{self.max_retries}), "
                    f"retrying in {wait_time}s..."
                )
                time.sleep(wait_time)
                return self._make_request(url, params, retry_count + 1)
            else:
                logger.error(f"Connection error after {self.max_retries} retries")
                raise APIError(
                    f"Failed to connect to {url}. "
                    "Check your internet connection or try again later."
                )

        except requests.exceptions.HTTPError as e:
            logger.error(f"HTTP error {e.response.status_code}: {e}")
            raise APIError(f"HTTP error {e.response.status_code}: {str(e)}")

        except requests.exceptions.RequestException as e:
            logger.error(f"Request failed: {e}")
            raise APIError(f"Request failed: {str(e)}")

        except ValueError as e:
            logger.error(f"Failed to parse JSON response: {e}")
            raise APIError(f"Invalid response format: {str(e)}")

        except Exception as e:
            logger.error(f"Unexpected error: {e}")
            raise APIError(f"Unexpected error: {str(e)}")

    def _make_post_request(
        self,
        url: str,
        json_data: Optional[Dict[str, Any]] = None,
        retry_count: int = 0,
    ) -> Optional[Dict[str, Any]]:
        """
        Make a POST request to the API with retry logic and comprehensive error handling.

        Args:
            url: URL to request
            json_data: JSON request body
            retry_count: Current retry attempt number (internal use)

        Returns:
            Response JSON or None if max retries exceeded

        Raises:
            BadRequestError: Invalid request parameters or malformed IDs (400)
            AuthenticationError: Invalid or expired token (401)
            RateLimitError: Daily limit reached (429)
            ServerError: Server error (5xx)
            APIError: Other API errors
        """
        headers: Dict[str, str] = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"

        try:
            logger.debug(f"Making POST request to {url} with json {json_data}")
            response = requests.post(
                url,
                json=json_data,
                headers=headers,
                timeout=self.timeout,
            )

            if response.status_code == 400:
                logger.warning(f"Bad request to {url}: {response.text}")
                raise BadRequestError(
                    "Invalid request. Please check your query or command arguments."
                )
            elif response.status_code == 401:
                logger.error("Authentication failed: Invalid or expired token")
                raise AuthenticationError(
                    "Invalid or expired token. Run 'deepxiv config' to set a valid token."
                )
            elif response.status_code == 429:
                logger.warning("Rate limit exceeded")
                raise RateLimitError(
                    "Daily limit reached. Visit https://data.rag.ac.cn/register for a higher limit."
                )
            elif response.status_code >= 500:
                logger.error(f"Server error {response.status_code}: {response.text}")
                raise ServerError(f"Server error {response.status_code}")

            response.raise_for_status()
            if not response.content:
                logger.debug(f"Empty response body from {url}")
                return {}
            result = response.json()
            logger.debug(f"Successfully received POST response from {url}")
            return result

        except APIError:
            raise

        except requests.exceptions.Timeout:
            if retry_count < self.max_retries:
                wait_time = self.retry_delay * (2 ** retry_count)
                logger.warning(
                    f"POST request timeout (attempt {retry_count + 1}/{self.max_retries}), "
                    f"retrying in {wait_time}s..."
                )
                time.sleep(wait_time)
                return self._make_post_request(url, json_data, retry_count + 1)
            raise APIError(
                f"Request timed out after {self.max_retries} retries. "
                "Check your internet connection or try again later."
            )

        except requests.exceptions.ConnectionError:
            if retry_count < self.max_retries:
                wait_time = self.retry_delay * (2 ** retry_count)
                logger.warning(
                    f"POST connection error (attempt {retry_count + 1}/{self.max_retries}), "
                    f"retrying in {wait_time}s..."
                )
                time.sleep(wait_time)
                return self._make_post_request(url, json_data, retry_count + 1)
            raise APIError(
                f"Failed to connect to {url}. "
                "Check your internet connection or try again later."
            )

        except requests.exceptions.HTTPError as e:
            logger.error(f"HTTP error {e.response.status_code}: {e}")
            raise APIError(f"HTTP error {e.response.status_code}: {str(e)}")

        except requests.exceptions.RequestException as e:
            logger.error(f"Request failed: {e}")
            raise APIError(f"Request failed: {str(e)}")

        except ValueError as e:
            logger.error(f"Failed to parse JSON response: {e}")
            raise APIError(f"Invalid response format: {str(e)}")

        except Exception as e:
            logger.error(f"Unexpected error: {e}")
            raise APIError(f"Unexpected error: {str(e)}")

    def search(
        self,
        query: str,
        size: int = 10,
        offset: int = 0,
        search_mode: str = "hybrid",
        bm25_weight: float = 0.5,
        vector_weight: float = 0.5,
        categories: Optional[List[str]] = None,
        authors: Optional[List[str]] = None,
        min_citation: Optional[int] = None,
        date_from: Optional[str] = None,
        date_to: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Search for papers using hybrid search (BM25 + Vector).

        Args:
            query: Search query string
            size: Number of results to return (default: 10, max: 100)
            offset: Result offset for pagination (default: 0)
            search_mode: Search mode - "bm25", "vector", or "hybrid" (default: "hybrid")
            bm25_weight: BM25 weight for hybrid search (default: 0.5)
            vector_weight: Vector weight for hybrid search (default: 0.5)
            categories: Filter by categories (e.g., ["cs.AI", "cs.CL"])
            authors: Filter by authors
            min_citation: Minimum citation count
            date_from: Publication date from (format: YYYY-MM-DD)
            date_to: Publication date to (format: YYYY-MM-DD)

        Returns:
            Dictionary with search results including 'total', 'took', and 'results' fields

        Raises:
            APIError: If the request fails
        """
        if not query or not query.strip():
            raise ValueError("Query cannot be empty")
        if size < 1 or size > 100:
            raise ValueError("Size must be between 1 and 100")
        if offset < 0:
            raise ValueError("Offset must be non-negative")

        params: Dict[str, Any] = {
            "type": "retrieve",
            "query": query,
            "size": size,
            "offset": offset,
            "search_mode": search_mode,
        }

        if search_mode == "hybrid":
            params["bm25_weight"] = bm25_weight
            params["vector_weight"] = vector_weight

        if categories:
            params["categories"] = ",".join(categories)
        if authors:
            params["authors"] = ",".join(authors)
        if min_citation is not None:
            params["min_citation"] = min_citation
        if date_from:
            params["date_from"] = date_from
        if date_to:
            params["date_to"] = date_to

        result = self._make_request(self.arxiv_endpoint, params=params)
        logger.info(f"Search for '{query}' returned {result.get('total', 0)} results")
        return result or {"total": 0, "results": []}

    def websearch(self, query: str) -> Dict[str, Any]:
        """
        Search the web with the DeepXiv websearch endpoint.

        Args:
            query: Search query string

        Returns:
            Dictionary response from the websearch endpoint

        Raises:
            APIError: If the request fails
        """
        if not query or not query.strip():
            raise ValueError("Query cannot be empty")

        result = self._make_post_request(
            self.websearch_endpoint,
            json_data={"query": query},
        )
        logger.info(f"Websearch for '{query}' completed")
        return result or {}

    def semantic_scholar(self, semantic_scholar_id: str) -> Dict[str, Any]:
        """
        Get paper information by Semantic Scholar ID.

        Args:
            semantic_scholar_id: Semantic Scholar paper ID (e.g., "258001")

        Returns:
            Dictionary response from the semantic scholar endpoint

        Raises:
            APIError: If the request fails
        """
        if not semantic_scholar_id or not str(semantic_scholar_id).strip():
            raise ValueError("semantic_scholar_id cannot be empty")

        params: Dict[str, Any] = {
            "id": str(semantic_scholar_id).strip(),
            "token": self.token or "",
        }
        result = self._make_request(self.semantic_scholar_endpoint, params=params)
        logger.info(f"Semantic Scholar lookup for '{semantic_scholar_id}' completed")
        return result or {}

    def head(self, arxiv_id: str) -> Dict[str, Any]:
        """
        Get paper metadata and structure (head information).

        Args:
            arxiv_id: arXiv ID (e.g., "2409.05591", "2504.21776")

        Returns:
            Dictionary with paper head information including:
            - title: Paper title
            - abstract: Paper abstract
            - authors: List of authors
            - sections: List of section information
            - token_count: Total tokens in the paper
            - categories: arXiv categories
            - publish_at: Publication date

        Raises:
            APIError: If the request fails
        """
        if not arxiv_id or not arxiv_id.strip():
            raise ValueError("arxiv_id cannot be empty")

        params: Dict[str, Any] = {"arxiv_id": arxiv_id, "type": "head"}
        result = self._make_request(self.arxiv_endpoint, params=params)
        return result or {}

    def brief(self, arxiv_id: str) -> Dict[str, Any]:
        """
        Get brief paper information (concise summary for quick overview).

        Args:
            arxiv_id: arXiv ID (e.g., "2409.05591", "2504.21776")

        Returns:
            Dictionary with brief paper information including:
            - arxiv_id: arXiv paper ID
            - title: Paper title
            - tldr: AI-generated summary (if available)
            - keywords: List of keywords (if available)
            - publish_at: Publication date
            - citations: Citation count
            - src_url: Direct link to PDF
            - github_url: Associated GitHub repository URL (if available)

        Raises:
            APIError: If the request fails
        """
        if not arxiv_id or not arxiv_id.strip():
            raise ValueError("arxiv_id cannot be empty")

        params: Dict[str, Any] = {"arxiv_id": arxiv_id, "type": "brief"}
        result = self._make_request(self.arxiv_endpoint, params=params)
        return result or {}

    def _match_section_name(self, arxiv_id: str, section_name: str) -> Optional[str]:
        """
        Match user input to actual section name (case-insensitive, partial match).

        Args:
            arxiv_id: arXiv ID
            section_name: User-provided section name (e.g., "Introduction", "introduction")

        Returns:
            Matched section name or None if not found
        """
        head = self.head(arxiv_id)
        if not head or "sections" not in head:
            return None

        sections: List[Dict[str, Any]] = head.get("sections", [])
        section_lower = section_name.lower()

        # Extract section names
        section_names = [
            section["name"] if isinstance(section, dict) else str(section)
            for section in sections
        ]

        # Try exact match first (case-insensitive)
        for name in section_names:
            if name.lower() == section_lower:
                return name

        # Try partial match (section name contains the query)
        for name in section_names:
            # Remove leading numbers like "1. " or "2. "
            clean_name = name.lower()
            if clean_name.startswith(tuple(f"{i}. " for i in range(10))):
                clean_name = clean_name[3:]

            if clean_name == section_lower or section_lower in clean_name:
                return name

        logger.warning(
            f"Section '{section_name}' not found in paper {arxiv_id}. "
            f"Available sections: {', '.join(section_names)}"
        )
        return None

    def section(self, arxiv_id: str, section_name: str) -> str:
        """
        Get a specific section content from a paper.

        Args:
            arxiv_id: arXiv ID (e.g., "2409.05591")
            section_name: Name of the section (e.g., "Introduction", "introduction", "Method")
                         Case-insensitive, partial match supported.

        Returns:
            Section content as string

        Raises:
            APIError: If the request fails
            ValueError: If section is not found
        """
        if not arxiv_id or not arxiv_id.strip():
            raise ValueError("arxiv_id cannot be empty")
        if not section_name or not section_name.strip():
            raise ValueError("section_name cannot be empty")

        # Match section name (case-insensitive)
        matched_name = self._match_section_name(arxiv_id, section_name)
        if not matched_name:
            raise ValueError(
                f"Section '{section_name}' not found in paper {arxiv_id}"
            )

        params: Dict[str, Any] = {
            "arxiv_id": arxiv_id,
            "type": "section",
            "section": matched_name,
        }
        result = self._make_request(self.arxiv_endpoint, params=params)

        return result.get("content", "") if result else ""

    def raw(self, arxiv_id: str) -> str:
        """
        Get the full paper content in markdown format.

        Args:
            arxiv_id: arXiv ID (e.g., "2409.05591")

        Returns:
            Full paper content as markdown string

        Raises:
            APIError: If the request fails
        """
        if not arxiv_id or not arxiv_id.strip():
            raise ValueError("arxiv_id cannot be empty")

        params: Dict[str, Any] = {"arxiv_id": arxiv_id, "type": "raw"}
        result = self._make_request(self.arxiv_endpoint, params=params)

        return result.get("raw", "") if result else ""

    def preview(self, arxiv_id: str) -> Dict[str, Any]:
        """
        Get a preview of the paper (first 10,000 characters).
        Useful for mobile devices or when you want to quickly scan the introduction.

        Args:
            arxiv_id: arXiv ID (e.g., "2409.05591")

        Returns:
            Dictionary with preview information including:
            - content: First 10,000 characters
            - is_truncated: Whether content was truncated
            - total_characters: Total characters in full document

        Raises:
            APIError: If the request fails
        """
        if not arxiv_id or not arxiv_id.strip():
            raise ValueError("arxiv_id cannot be empty")

        params: Dict[str, Any] = {"arxiv_id": arxiv_id, "type": "preview"}
        result = self._make_request(self.arxiv_endpoint, params=params)

        return result or {"content": "", "is_truncated": False}

    def json(self, arxiv_id: str) -> Dict[str, Any]:
        """
        Get the complete structured JSON file with all sections and metadata.

        Args:
            arxiv_id: arXiv ID (e.g., "2409.05591")

        Returns:
            Complete structured JSON with all paper data

        Raises:
            APIError: If the request fails
        """
        if not arxiv_id or not arxiv_id.strip():
            raise ValueError("arxiv_id cannot be empty")

        params: Dict[str, Any] = {"arxiv_id": arxiv_id, "type": "json"}
        result = self._make_request(self.arxiv_endpoint, params=params)

        return result or {}

    def markdown(self, arxiv_id: str) -> str:
        """
        Get the HTML view URL for the paper.

        Args:
            arxiv_id: arXiv ID (e.g., "2409.05591")

        Returns:
            URL to the HTML view of the paper
        """
        if not arxiv_id or not arxiv_id.strip():
            raise ValueError("arxiv_id cannot be empty")

        return f"https://arxiv.org/html/{arxiv_id}"

    # ========== PMC (PubMed Central) Methods ==========

    def pmc_head(self, pmc_id: str) -> Dict[str, Any]:
        """
        Get PMC paper metadata (title, abstract, authors, categories, publication date).

        Args:
            pmc_id: PMC ID (e.g., "PMC544940", "PMC514704")

        Returns:
            Dictionary with PMC paper metadata including:
            - pmc_id: PMC paper ID
            - title: Paper title
            - doi: Digital Object Identifier
            - abstract: Paper abstract
            - authors: List of authors
            - categories: Medical subject categories
            - publish_at: Publication date

        Raises:
            APIError: If the request fails
        """
        if not pmc_id or not pmc_id.strip():
            raise ValueError("pmc_id cannot be empty")

        params: Dict[str, Any] = {"pmc_id": pmc_id, "type": "head"}
        result = self._make_request(self.pmc_endpoint, params=params)

        return result or {}

    def pmc_full(self, pmc_id: str) -> Dict[str, Any]:
        """
        Get the complete PMC paper in structured JSON format with full content and metadata.

        Args:
            pmc_id: PMC ID (e.g., "PMC544940", "PMC514704")

        Returns:
            Complete structured JSON with all PMC paper data

        Raises:
            APIError: If the request fails
        """
        if not pmc_id or not pmc_id.strip():
            raise ValueError("pmc_id cannot be empty")

        params: Dict[str, Any] = {"pmc_id": pmc_id, "type": "json"}
        result = self._make_request(self.pmc_endpoint, params=params)

        return result or {}

    # Alias for backwards compatibility
    def pmc_json(self, pmc_id: str) -> Dict[str, Any]:
        """Alias for pmc_full(). Get the complete PMC paper in JSON format."""
        return self.pmc_full(pmc_id)

    # ========== Trending Methods ==========

    def trending(
        self,
        days: int = 7,
        limit: int = 30,
    ) -> Dict[str, Any]:
        """
        Get trending arXiv papers.

        Args:
            days: Number of days to look back (7, 14, or 30). Default: 7
            limit: Maximum number of papers to return. Default: 30

        Returns:
            Dictionary with trending papers including:
            - papers: List of trending paper objects with metadata
            - total: Total number of trending papers available
            - days: The days parameter used
            - generated_at: Timestamp when the trending list was generated

        Raises:
            ValueError: If days or limit are invalid
            APIError: If the request fails
        """
        if days not in [7, 14, 30]:
            raise ValueError("days must be 7, 14, or 30")
        if limit < 1 or limit > 100:
            raise ValueError("limit must be between 1 and 100")

        # Use the trending API endpoint (no token required)
        trending_url = "https://api.rag.ac.cn/trending_arxiv_papers/api/trending"
        params: Dict[str, Any] = {
            "days": days,
            "limit": limit,
        }

        result = self._make_request(trending_url, params=params)

        # Extract data from nested response structure
        if result and "data" in result:
            data = result["data"]
            logger.info(f"Retrieved {len(data.get('papers', []))} trending papers for last {days} days")
            return data

        logger.info(f"No trending data available for {days} days")
        return {"papers": [], "total": 0}

    # ========== Social Impact Methods ==========

    def social_impact(self, arxiv_id: str) -> Optional[Dict[str, Any]]:
        """
        Get social media impact metrics for an arXiv paper (trending signal).

        Args:
            arxiv_id: arXiv ID (e.g., "2409.05592", "2506.00002")

        Returns:
            Dictionary with social impact metrics including:
            - total_tweets: Number of tweets mentioning the paper
            - total_likes: Total likes across social media
            - total_views: Number of views
            - total_replies: Number of replies/comments
            - first_seen_date: When the paper first appeared in trending (ISO format)
            - last_seen_date: Most recent trending activity (ISO format)
            - arxiv_id: The paper ID

            Returns None if no data is found for the paper.

        Raises:
            ValueError: If arxiv_id is invalid
            AuthenticationError: If token is missing or invalid (required for this endpoint)
            APIError: If the request fails
        """
        if not arxiv_id or not arxiv_id.strip():
            raise ValueError("arxiv_id cannot be empty")

        if not self.token:
            raise AuthenticationError(
                "Token is required for social impact queries. "
                "Provide a token when initializing Reader: Reader(token='your_token')"
            )

        params: Dict[str, Any] = {
            "arxiv_id": arxiv_id,
            "token": self.token,
        }
        try:
            # Social impact data is served from the data.rag.ac.cn domain and
            # expects the token in query params for compatibility.
            signal_url = f"{self.base_url}/arxiv/trending_signal"
            result = self._make_request(signal_url, params=params)
            logger.info(f"Retrieved social impact metrics for {arxiv_id}")
            return result or None
        except NotFoundError:
            logger.warning(f"No social impact data found for {arxiv_id}")
            return None


# ---------------------------------------------------------------------------
# Agent tools (data.rag.ac.cn Reader)
# ---------------------------------------------------------------------------

_DEFAULT_MAX_CHARS = 48_000


def get_deepxiv_reader() -> Reader:
    """Return a Reader; token from ``DEEPXIV_API_KEY`` if not passed to Reader."""
    return Reader()


def _normalize_arxiv_id(raw: str) -> str:
    s = (raw or "").strip()
    if not s:
        return ""
    s = s.replace("https://", "").replace("http://", "")
    if "arxiv.org/abs/" in s:
        s = s.split("arxiv.org/abs/")[-1]
    elif "arxiv.org/pdf/" in s:
        s = s.split("arxiv.org/pdf/")[-1].replace(".pdf", "")
    s = s.strip().strip("/")
    if "/" in s and not s.startswith("10."):
        s = s.split("/")[-1]
    return s


def _json_text(obj: Any, *, max_chars: int = _DEFAULT_MAX_CHARS) -> str:
    try:
        text = json.dumps(obj, ensure_ascii=False, indent=2, default=str)
    except TypeError:
        text = str(obj)
    if len(text) > max_chars:
        return text[: max_chars - 40].rstrip() + "\n… [output truncated]"
    return text


def _format_search_results(data: Dict[str, Any]) -> str:
    total = data.get("total", 0)
    took = data.get("took")
    results = data.get("results") or []
    head = f"DeepXiv hybrid search — total={total}"
    if took is not None:
        head += f", took_ms={took}"
    lines: list[str] = [head, ""]
    for i, item in enumerate(results, 1):
        if not isinstance(item, dict):
            lines.append(f"{i}. {item}")
            continue
        title = item.get("title") or item.get("paper_title") or "(no title)"
        aid = item.get("arxiv_id") or item.get("arxiv") or item.get("id") or ""
        score = item.get("score")
        extra = []
        if score is not None:
            extra.append(f"score={score}")
        cat = item.get("categories") or item.get("category")
        if cat:
            extra.append(f"cat={cat}")
        line = f"{i}. {title}"
        if aid:
            line += f"  [{aid}]"
        if extra:
            line += f"  ({', '.join(extra)})"
        lines.append(line)
        abst = item.get("abstract") or item.get("summary") or item.get("snippet")
        if isinstance(abst, str) and abst.strip():
            one = " ".join(abst.split())
            if len(one) > 500:
                one = one[:497] + "…"
            lines.append(f"   {one}")
        lines.append("")
    return "\n".join(lines).strip()


def _format_trending(data: Dict[str, Any]) -> str:
    papers = data.get("papers") or []
    meta: list[str] = ["DeepXiv trending arXiv"]
    if data.get("days") is not None:
        meta.append(f"days={data.get('days')}")
    if data.get("total") is not None:
        meta.append(f"total={data.get('total')}")
    if data.get("generated_at"):
        meta.append(f"generated_at={data.get('generated_at')}")
    lines: list[str] = [" — ".join(meta), ""]
    for i, p in enumerate(papers, 1):
        if not isinstance(p, dict):
            lines.append(f"{i}. {p}")
            continue
        title = p.get("title") or "(no title)"
        aid = p.get("arxiv_id") or p.get("id") or ""
        line = f"{i}. {title}"
        if aid:
            line += f"  [{aid}]"
        lines.append(line)
        for key in ("reason", "summary", "abstract"):
            v = p.get(key)
            if isinstance(v, str) and v.strip():
                one = " ".join(v.split())
                if len(one) > 400:
                    one = one[:397] + "…"
                lines.append(f"   {key}: {one}")
                break
        lines.append("")
    return "\n".join(lines).strip()


def _run_read(
    reader: Reader,
    arxiv_id: str,
    mode: str,
    *,
    section_name: str | None,
    max_chars: int,
) -> str:
    m = (mode or "head").strip().lower()
    if m == "head":
        return _json_text(reader.head(arxiv_id), max_chars=max_chars)
    if m == "brief":
        return _json_text(reader.brief(arxiv_id), max_chars=max_chars)
    if m == "section":
        if not section_name or not str(section_name).strip():
            return "Error: mode=section requires non-empty section_name"
        return reader.section(arxiv_id, section_name.strip())
    if m == "raw":
        body = reader.raw(arxiv_id)
        if len(body) > max_chars:
            return body[: max_chars - 40].rstrip() + "\n… [truncated]"
        return body
    if m == "preview":
        return _json_text(reader.preview(arxiv_id), max_chars=max_chars)
    if m == "json":
        return _json_text(reader.json(arxiv_id), max_chars=max_chars)
    if m == "markdown":
        return reader.markdown(arxiv_id)
    if m in ("social_impact", "trending_signal", "social"):
        try:
            out = reader.social_impact(arxiv_id)
        except AuthenticationError as e:
            return f"Error: {e}"
        if out is None:
            return "(no social impact / trending signal data for this id)"
        return _json_text(out, max_chars=max_chars)
    return (
        f"Error: unknown mode {mode!r}. Use head, brief, section, raw, preview, json, "
        "markdown, social_impact."
    )


class DeepxivArxivSearchTool(Tool):
    """BM25 + vector hybrid search via data.rag.ac.cn (Reader.search)."""

    name = "deepxiv_arxiv_search"
    description = (
        "DeepXiv academic search over arXiv (hybrid BM25+vector at data.rag.ac.cn). "
        "Use for semantic/concept queries when official arxiv_search is not enough. "
        "search_mode: bm25 | vector | hybrid (default hybrid). Optional filters: categories, "
        "authors, min_citation, date_from/date_to (YYYY-MM-DD)."
    )

    parameters = {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Natural language or keyword query.",
            },
            "size": {
                "type": "integer",
                "description": "Result count 1–100 (default 10).",
                "minimum": 1,
                "maximum": 100,
            },
            "offset": {
                "type": "integer",
                "description": "Pagination offset (default 0).",
                "minimum": 0,
            },
            "search_mode": {
                "type": "string",
                "enum": ["hybrid", "bm25", "vector"],
                "description": "Retrieval mode (default hybrid).",
            },
            "bm25_weight": {
                "type": "number",
                "description": "BM25 weight for hybrid (default 0.5).",
            },
            "vector_weight": {
                "type": "number",
                "description": "Vector weight for hybrid (default 0.5).",
            },
            "categories": {
                "type": "string",
                "description": "Comma-separated arXiv categories, e.g. cs.AI,cs.CL",
            },
            "authors": {
                "type": "string",
                "description": "Comma-separated author name filters.",
            },
            "min_citation": {
                "type": "integer",
                "description": "Minimum citation count filter.",
            },
            "date_from": {
                "type": "string",
                "description": "Publication date from (YYYY-MM-DD).",
            },
            "date_to": {
                "type": "string",
                "description": "Publication date to (YYYY-MM-DD).",
            },
        },
        "required": ["query"],
    }

    async def execute(self, **kwargs: Any) -> str:
        q = (kwargs.get("query") or "").strip()
        if not q:
            return "Error: query is required"

        size = kwargs.get("size", 10)
        try:
            size_i = int(float(size))
        except (TypeError, ValueError):
            size_i = 10
        size_i = max(1, min(size_i, 100))

        off = kwargs.get("offset", 0)
        try:
            off_i = int(float(off))
        except (TypeError, ValueError):
            off_i = 0
        off_i = max(0, off_i)

        mode = kwargs.get("search_mode") or "hybrid"
        if mode not in ("hybrid", "bm25", "vector"):
            return "Error: search_mode must be hybrid, bm25, or vector"

        def _f(name: str, default: float) -> float:
            v = kwargs.get(name, default)
            try:
                return float(v)
            except (TypeError, ValueError):
                return default

        bw = _f("bm25_weight", 0.5)
        vw = _f("vector_weight", 0.5)

        cats_raw = kwargs.get("categories")
        categories: list[str] | None = None
        if isinstance(cats_raw, str) and cats_raw.strip():
            categories = [c.strip() for c in cats_raw.split(",") if c.strip()]

        auth_raw = kwargs.get("authors")
        authors: list[str] | None = None
        if isinstance(auth_raw, str) and auth_raw.strip():
            authors = [a.strip() for a in auth_raw.split(",") if a.strip()]

        min_cit = kwargs.get("min_citation")
        min_citation: int | None = None
        if min_cit is not None:
            try:
                min_citation = int(float(min_cit))
            except (TypeError, ValueError):
                min_citation = None

        date_from = kwargs.get("date_from")
        date_to = kwargs.get("date_to")
        df = date_from.strip() if isinstance(date_from, str) else None
        dt = date_to.strip() if isinstance(date_to, str) else None

        def _work() -> str:
            r = get_deepxiv_reader()
            data = r.search(
                q,
                size=size_i,
                offset=off_i,
                search_mode=mode,
                bm25_weight=bw,
                vector_weight=vw,
                categories=categories,
                authors=authors,
                min_citation=min_citation,
                date_from=df or None,
                date_to=dt or None,
            )
            return _format_search_results(data)

        try:
            return await asyncio.to_thread(_work)
        except ValueError as e:
            return f"Error: {e}"
        except APIError as e:
            return f"Error: {e}"
        except Exception as e:
            loguru_logger.exception("deepxiv_arxiv_search")
            return f"Error: {e}"


class DeepxivArxivTrendingTool(Tool):
    """Trending arXiv papers (Reader.trending)."""

    name = "deepxiv_arxiv_trending"
    description = (
        "Trending arXiv papers from DeepXiv (last 7 / 14 / 30 days). "
        "No API key required for the public trending list."
    )

    parameters = {
        "type": "object",
        "properties": {
            "days": {
                "type": "string",
                "description": "Lookback window: '7', '14', or '30' (default 7).",
                "enum": ["7", "14", "30"],
            },
            "limit": {
                "type": "integer",
                "description": "Max papers 1–100 (default 30).",
                "minimum": 1,
                "maximum": 100,
            },
        },
        "required": [],
    }

    async def execute(self, **kwargs: Any) -> str:
        days = kwargs.get("days", 7)
        try:
            days_i = int(float(days))
        except (TypeError, ValueError):
            days_i = 7
        if days_i not in (7, 14, 30):
            return "Error: days must be 7, 14, or 30"

        lim = kwargs.get("limit", 30)
        try:
            lim_i = int(float(lim))
        except (TypeError, ValueError):
            lim_i = 30
        lim_i = max(1, min(lim_i, 100))

        def _work() -> str:
            r = get_deepxiv_reader()
            data = r.trending(days=days_i, limit=lim_i)
            return _format_trending(data)

        try:
            return await asyncio.to_thread(_work)
        except ValueError as e:
            return f"Error: {e}"
        except APIError as e:
            return f"Error: {e}"
        except Exception as e:
            loguru_logger.exception("deepxiv_arxiv_trending")
            return f"Error: {e}"


class DeepxivArxivReadTool(Tool):
    """Read arXiv paper content/metadata via DeepXiv (multiple modes)."""

    name = "deepxiv_arxiv_read"
    description = (
        "Read an arXiv paper through DeepXiv (data.rag.ac.cn): metadata (head), AI brief, "
        "single section, full raw markdown, preview (~10k chars), structured json, HTML view URL, "
        "or social/trending signal. "
        "arxiv_id like 2409.05591 or a full /abs/ URL."
    )

    parameters = {
        "type": "object",
        "properties": {
            "arxiv_id": {
                "type": "string",
                "description": "arXiv id (e.g. 2409.05591) or URL to arxiv.org/abs/...",
            },
            "mode": {
                "type": "string",
                "enum": [
                    "head",
                    "brief",
                    "section",
                    "raw",
                    "preview",
                    "json",
                    "markdown",
                    "social_impact",
                ],
                "description": (
                    "head=metadata+sections outline; brief=TL;DR-style; section=needs section_name; "
                    "raw=full MD; preview=short excerpt; json=structured; markdown=arXiv HTML URL; "
                    "social_impact=tweets/views signal (token required)."
                ),
            },
            "section_name": {
                "type": "string",
                "description": "Required when mode=section (e.g. Introduction, Method).",
            },
            "max_chars": {
                "type": "integer",
                "description": f"Max characters for JSON/text modes (default {_DEFAULT_MAX_CHARS}).",
                "minimum": 2000,
                "maximum": 200_000,
            },
        },
        "required": ["arxiv_id"],
    }

    async def execute(self, **kwargs: Any) -> str:
        aid = _normalize_arxiv_id(str(kwargs.get("arxiv_id") or ""))
        if not aid:
            return "Error: arxiv_id is required"

        mode = kwargs.get("mode") or "head"
        sec = kwargs.get("section_name")
        mc = kwargs.get("max_chars", _DEFAULT_MAX_CHARS)
        try:
            max_chars = int(float(mc))
        except (TypeError, ValueError):
            max_chars = _DEFAULT_MAX_CHARS
        max_chars = max(2000, min(max_chars, 200_000))

        def _work() -> str:
            r = get_deepxiv_reader()
            return _run_read(r, aid, str(mode), section_name=sec, max_chars=max_chars)

        try:
            return await asyncio.to_thread(_work)
        except ValueError as e:
            return f"Error: {e}"
        except APIError as e:
            return f"Error: {e}"
        except Exception as e:
            loguru_logger.exception("deepxiv_arxiv_read")
            return f"Error: {e}"


__all__ = [
    "APIError",
    "AuthenticationError",
    "BadRequestError",
    "DeepxivArxivReadTool",
    "DeepxivArxivSearchTool",
    "DeepxivArxivTrendingTool",
    "NotFoundError",
    "RateLimitError",
    "Reader",
    "ServerError",
    "get_deepxiv_reader",
]